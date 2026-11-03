package com.aidexx.reader.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.aidexx.reader.protocol.AidexCrypto
import com.aidexx.reader.protocol.AidexF003Parser
import com.aidexx.reader.protocol.AidexHistoryProtocol
import com.aidexx.reader.protocol.AidexProtocol
import com.aidexx.reader.protocol.AidexReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LiveGattStage {
    IDLE,
    CONNECTING,
    DISCOVERING,
    ENABLING_F003,
    ENABLING_F002,
    ENABLING_F001,
    BONDING,
    REQUESTING_MASTER_KEY,
    READING_SESSION_KEY,
    INITIALIZING_PROTOCOL,
    ACTIVATION_REQUIRED,
    WAITING_FOR_CURRENT,
    STREAMING,
    RETRY_WAIT,
    FAILED
}

data class LiveGattState(
    val stage: LiveGattStage =
        LiveGattStage.IDLE,
    val connected: Boolean =
        false,
    val streaming: Boolean =
        false,
    val lastPacketAtMs: Long =
        0L,
    val lastSensorMinute: Int =
        -1,
    val lastCrcValid: Boolean? =
        null,
    val reconnectCount: Int =
        0,
    val protocolStep: String? =
        null,
    val lastF002Response: Int =
        -1,
    val lastF002PayloadHex: String? =
        null,
    val sensorActivationState: String =
        "UNKNOWN",
    val sensorStartYear: Int =
        -1,
    val f003Properties: Int =
        0,
    val f003LocalNotifyEnabled: Boolean? =
        null,
    val f003CccdWriteStatus: Int =
        -1,
    val f003CccdWriteCount: Int =
        0,
    val f003NotificationCount: Int =
        0,
    val f003LastRawAtMs: Long =
        0L,
    val f003LastRawLength: Int =
        -1,
    val f003LastSource: String? =
        null,
    val f003ReadSupported: Boolean =
        false,
    val f003ReadAttempts: Int =
        0,
    val f003LastReadStatus: Int =
        -1,
    val message: String =
        "Secure live GATT idle",
    val error: String? =
        null
)

/**
 * Secure AiDEX X live-current client.
 *
 * F003 is encrypted. A usable F003 stream therefore requires the same
 * F001/F002 key exchange used by encrypted history:
 *
 * 1. Enable notifications F003 -> F002 -> F001.
 * 2. Write serial-derived key request to F001.
 * 3. Receive 16-byte KEY0 on F001.
 * 4. Read 17-byte encrypted KEY1 from F002.
 * 5. AES-CFB decrypt KEY1 with KEY0 + serial IV and validate CRC8/MAXIM.
 * 6. Send encrypted protocol initialization over F002.
 * 7. AES-CFB decrypt every F003 packet with KEY1 + serial IV.
 * 8. CRC16 validate clear F003 and parse current glucose.
 */
class AidexLiveGattClient(
    private val context: Context,
    private val onReading:
        (
            AidexReading
        ) -> Unit
) {
    private val appContext =
        context.applicationContext

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val _state =
        MutableStateFlow(
            LiveGattState()
        )

    val state:
        StateFlow<LiveGattState> =
        _state

    private var gatt:
        BluetoothGatt? =
        null

    private var device:
        BluetoothDevice? =
        null

    private var f001:
        BluetoothGattCharacteristic? =
        null

    private var f002:
        BluetoothGattCharacteristic? =
        null

    private var f003:
        BluetoothGattCharacteristic? =
        null

    private var serial =
        ""

    private var address =
        ""

    private var iv =
        byteArrayOf()

    private var key0:
        ByteArray? =
        null

    private var key1:
        ByteArray? =
        null

    private var stopped =
        true

    private var reconnectCount =
        0

    private var notifyStep =
        NotifyStep.NONE

    private var f001WriteCompleted =
        false

    private var f001MasterKeyReceived =
        false

    private var sessionKeyReadStarted =
        false

    private var sessionKeyReadRetryCount =
        0

    private var protocolInitStep =
        ProtocolInitStep.NONE

    private var rearmingF003 =
        false

    private var f003ReadRetryCount =
        0

    private var waitingForBond =
        false

    private var bondRetryStep =
        BondRetryStep.NONE

    private enum class BondRetryStep {
        NONE,
        ENABLE_F003,
        ENABLE_F002,
        ENABLE_F001,
        WRITE_F001,
        READ_F002
    }

    private enum class NotifyStep {
        NONE,
        F003,
        F002,
        F001
    }

    private enum class ProtocolInitStep {
        NONE,
        START_INFO,
        ASK_START_TIME,
        FINALIZE_35,
        FINALIZE_34,
        FINALIZE_11,
        VERIFY_START_TIME,
        VERIFY_HISTORY_POSITION,
        READY
    }

    private val bondReceiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED
                ) {
                    return
                }

                val changed =
                    if (
                        Build.VERSION.SDK_INT >=
                        33
                    ) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE
                        )
                    }

                if (
                    changed?.address !=
                    device?.address
                ) {
                    return
                }

                when (
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR
                    )
                ) {
                    BluetoothDevice.BOND_BONDING ->
                        update(
                            stage =
                                LiveGattStage.BONDING,
                            connected =
                                true,
                            message =
                                "Secure Bluetooth bonding in progress…",
                            error =
                                null
                        )

                    BluetoothDevice.BOND_BONDED -> {
                        if (
                            !waitingForBond
                        ) {
                            return
                        }

                        waitingForBond =
                            false

                        update(
                            stage =
                                LiveGattStage.BONDING,
                            connected =
                                true,
                            message =
                                "Bluetooth bond established • resuming secure AiDEX session…",
                            error =
                                null
                        )

                        handler.postDelayed(
                            {
                                retryAfterBond()
                            },
                            BOND_RETRY_DELAY_MS
                        )
                    }

                    BluetoothDevice.BOND_NONE -> {
                        if (
                            waitingForBond
                        ) {
                            waitingForBond =
                                false

                            scheduleReconnect(
                                "Bluetooth secure bonding failed or was cancelled."
                            )
                        }
                    }
                }
            }
        }

    init {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        appContext.registerReceiver(
            bondReceiver,
            IntentFilter(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED
            )
        )
    }

    private val reconnectRunnable =
        Runnable {
            if (
                !stopped
            ) {
                connectNow()
            }
        }

    @SuppressLint("MissingPermission")
    fun start(
        serial: String,
        address: String
    ): Boolean {
        val normalizedSerial =
            serial
                .trim()
                .uppercase()

        val normalizedAddress =
            address
                .trim()
                .uppercase()

        if (
            !AidexProtocol
                .isValidSerial(
                    normalizedSerial
                ) ||
            normalizedAddress.isBlank()
        ) {
            update(
                stage =
                    LiveGattStage.FAILED,
                message =
                    "Secure live GATT cannot start without a valid AiDEX serial/address.",
                error =
                    "Invalid selected sensor serial/address."
            )

            return false
        }

        if (
            !hasConnectPermission()
        ) {
            update(
                stage =
                    LiveGattStage.FAILED,
                message =
                    "Bluetooth connect permission is missing.",
                error =
                    "BLUETOOTH_CONNECT not granted."
            )

            return false
        }

        val sensorChanged =
            !this.serial.equals(
                normalizedSerial,
                ignoreCase =
                    true
            )

        this.serial =
            normalizedSerial

        this.address =
            normalizedAddress

        this.iv =
            AidexCrypto
                .serialIv(
                    normalizedSerial
                )

        if (
            sensorChanged
        ) {
            reconnectCount =
                0
        }

        stopped =
            false

        handler.removeCallbacksAndMessages(
            null
        )

        closeGatt()

        resetSession()

        update(
            stage =
                LiveGattStage.CONNECTING,
            connected =
                false,
            streaming =
                false,
            lastPacketAtMs =
                0L,
            lastSensorMinute =
                -1,
            lastCrcValid =
                null,
            reconnectCount =
                reconnectCount,
            protocolStep =
                ProtocolInitStep.NONE.name,
            lastF002Response =
                -1,
            lastF002PayloadHex =
                null,
            sensorActivationState =
                "UNKNOWN",
            sensorStartYear =
                -1,
            f003Properties =
                0,
            f003LocalNotifyEnabled =
                null,
            f003CccdWriteStatus =
                -1,
            f003CccdWriteCount =
                0,
            f003NotificationCount =
                0,
            f003LastRawAtMs =
                0L,
            f003LastRawLength =
                -1,
            f003LastSource =
                null,
            f003ReadSupported =
                false,
            f003ReadAttempts =
                0,
            f003LastReadStatus =
                -1,
            message =
                "Starting secure live GATT for AiDEX X-$normalizedSerial…",
            error =
                null
        )

        return connectNow()
    }

    fun restart() {
        if (
            stopped
        ) {
            return
        }

        handler.removeCallbacksAndMessages(
            null
        )

        closeGatt()

        resetSession()

        reconnectCount +=
            1

        update(
            stage =
                LiveGattStage.RETRY_WAIT,
            connected =
                false,
            streaming =
                false,
            lastPacketAtMs =
                0L,
            lastSensorMinute =
                -1,
            lastCrcValid =
                null,
            reconnectCount =
                reconnectCount,
            protocolStep =
                ProtocolInitStep.NONE.name,
            lastF002Response =
                -1,
            lastF002PayloadHex =
                null,
            sensorActivationState =
                "UNKNOWN",
            sensorStartYear =
                -1,
            f003Properties =
                0,
            f003LocalNotifyEnabled =
                null,
            f003CccdWriteStatus =
                -1,
            f003CccdWriteCount =
                0,
            f003NotificationCount =
                0,
            f003LastRawAtMs =
                0L,
            f003LastRawLength =
                -1,
            f003LastSource =
                null,
            f003ReadSupported =
                false,
            f003ReadAttempts =
                0,
            f003LastReadStatus =
                -1,
            message =
                "Restarting secure live GATT…"
        )

        handler.postDelayed(
            reconnectRunnable,
            RECONNECT_DELAY_MS
        )
    }

    fun stop() {
        stopped =
            true

        handler.removeCallbacksAndMessages(
            null
        )

        closeGatt()

        resetSession()

        update(
            stage =
                LiveGattStage.IDLE,
            connected =
                false,
            streaming =
                false,
            message =
                "Secure live GATT stopped.",
            error =
                null
        )
    }

    private fun resetSession() {
        f001 =
            null

        f002 =
            null

        f003 =
            null

        key0 =
            null

        key1 =
            null

        notifyStep =
            NotifyStep.NONE

        f001WriteCompleted =
            false

        f001MasterKeyReceived =
            false

        sessionKeyReadStarted =
            false

        sessionKeyReadRetryCount =
            0

        protocolInitStep =
            ProtocolInitStep.NONE

        rearmingF003 =
            false

        f003ReadRetryCount =
            0

        waitingForBond =
            false

        bondRetryStep =
            BondRetryStep.NONE
    }

    @SuppressLint("MissingPermission")
    private fun connectNow(): Boolean {
        if (
            stopped
        ) {
            return false
        }

        val adapter =
            appContext
                .getSystemService(
                    BluetoothManager::class.java
                )
                ?.adapter

        if (
            adapter ==
            null ||
            !adapter.isEnabled
        ) {
            scheduleReconnect(
                "Bluetooth adapter unavailable or disabled."
            )

            return false
        }

        val device =
            runCatching {
                adapter.getRemoteDevice(
                    address
                )
            }
                .getOrNull()

        if (
            device ==
            null
        ) {
            scheduleReconnect(
                "Could not open selected BLE address $address."
            )

            return false
        }

        this.device =
            device

        update(
            stage =
                LiveGattStage.CONNECTING,
            connected =
                false,
            streaming =
                false,
            lastPacketAtMs =
                0L,
            lastSensorMinute =
                -1,
            lastCrcValid =
                null,
            message =
                "Connecting securely to AiDEX X…",
            error =
                null,
            reconnectCount =
                reconnectCount
        )

        touchTimeout()

        return runCatching {
            gatt =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {
                    device.connectGatt(
                        appContext,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    @Suppress(
                        "DEPRECATION"
                    )
                    device.connectGatt(
                        appContext,
                        false,
                        callback
                    )
                }

            gatt !=
                null
        }
            .getOrElse {
                scheduleReconnect(
                    "${
                        it.javaClass
                            .simpleName
                    }: ${
                        it.message
                            ?: "connectGatt failed"
                    }"
                )

                false
            }
    }

    private val callback =
        object :
            BluetoothGattCallback() {

            @SuppressLint(
                "MissingPermission"
            )
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (
                    stopped
                ) {
                    runCatching {
                        g.close()
                    }

                    return
                }

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS &&
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {
                    gatt =
                        g

                    update(
                        stage =
                            LiveGattStage.DISCOVERING,
                        connected =
                            true,
                        streaming =
                            false,
                        message =
                            "Connected • discovering encrypted AiDEX CGM characteristics…",
                        error =
                            null
                    )

                    touchTimeout()

                    val started =
                        runCatching {
                            g.discoverServices()
                        }
                            .getOrDefault(
                                false
                            )

                    if (
                        !started
                    ) {
                        scheduleReconnect(
                            "GATT service discovery could not start."
                        )
                    }

                    return
                }

                if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED ||
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    scheduleReconnect(
                        "Secure live GATT disconnected: status=$status state=$newState"
                    )
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int
            ) {
                touchTimeout()

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    scheduleReconnect(
                        "AiDEX service discovery failed: $status"
                    )

                    return
                }

                val service =
                    g.getService(
                        AidexProtocol
                            .SERVICE_UUID
                    )
                        ?: return scheduleReconnect(
                            "CGM service 0x181F not found."
                        )

                f001 =
                    service.getCharacteristic(
                        AidexProtocol
                            .F001_UUID
                    )

                f002 =
                    service.getCharacteristic(
                        AidexProtocol
                            .F002_UUID
                    )

                f003 =
                    service.getCharacteristic(
                        AidexProtocol
                            .F003_UUID
                    )

                if (
                    f001 ==
                    null ||
                    f002 ==
                    null ||
                    f003 ==
                    null
                ) {
                    scheduleReconnect(
                        "Required AiDEX F001/F002/F003 characteristics are missing."
                    )

                    return
                }

                val currentF003 =
                    f003
                        ?: return

                val currentF003Properties =
                    currentF003.properties

                _state.value =
                    _state.value.copy(
                        f003Properties =
                            currentF003Properties,
                        f003ReadSupported =
                            (
                                currentF003Properties and
                                    BluetoothGattCharacteristic.PROPERTY_READ
                                ) !=
                                0
                    )

                notifyStep =
                    NotifyStep.F003

                update(
                    stage =
                        LiveGattStage.ENABLING_F003,
                    connected =
                        true,
                    message =
                        "Enabling encrypted F003 current notifications…"
                )

                enableNotify(
                    g,
                    f003
                        ?: return
                )
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor:
                    BluetoothGattDescriptor,
                status: Int
            ) {
                touchTimeout()

                val descriptorUuid =
                    descriptor
                        .characteristic
                        .uuid

                if (
                    descriptorUuid ==
                    AidexProtocol.F003_UUID
                ) {
                    _state.value =
                        _state.value.copy(
                            f003CccdWriteStatus =
                                status,
                            f003CccdWriteCount =
                                _state.value.f003CccdWriteCount +
                                    1
                        )

                    if (
                        rearmingF003 &&
                        _state.value.streaming
                    ) {
                        rearmingF003 =
                            false

                        notifyStep =
                            NotifyStep.NONE

                        return
                    }
                }

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    if (
                        isAuthenticationStatus(
                            status
                        )
                    ) {
                        val retry =
                            when (
                                descriptor
                                    .characteristic
                                    .uuid
                            ) {
                                AidexProtocol.F003_UUID ->
                                    BondRetryStep.ENABLE_F003

                                AidexProtocol.F002_UUID ->
                                    BondRetryStep.ENABLE_F002

                                AidexProtocol.F001_UUID ->
                                    BondRetryStep.ENABLE_F001

                                else ->
                                    BondRetryStep.NONE
                            }

                        if (
                            retry !=
                            BondRetryStep.NONE
                        ) {
                            requestSecureBond(
                                retryStep =
                                    retry,
                                reason =
                                    "Sensor requested encrypted Bluetooth access while enabling ${descriptor.characteristic.uuid}."
                            )
                            return
                        }
                    }

                    scheduleReconnect(
                        "AiDEX notification setup failed for ${descriptor.characteristic.uuid}: $status"
                    )

                    return
                }

                when (
                    descriptor
                        .characteristic
                        .uuid
                ) {
                    AidexProtocol
                        .F003_UUID -> {
                        if (
                            rearmingF003
                        ) {
                            rearmingF003 =
                                false

                            notifyStep =
                                NotifyStep.NONE

                            update(
                                stage =
                                    LiveGattStage.WAITING_FOR_CURRENT,
                                connected =
                                    true,
                                message =
                                    "F003 subscription re-armed after sensor-state confirmation • waiting for current glucose…",
                                error =
                                    null
                            )

                            handler.postDelayed(
                                {
                                    attemptF003ReadIfSupported()
                                },
                                F003_READ_AFTER_REARM_DELAY_MS
                            )

                            touchCurrentTimeout()

                            return
                        }

                        notifyStep =
                            NotifyStep.F002

                        update(
                            stage =
                                LiveGattStage.ENABLING_F002,
                            connected =
                                true,
                            message =
                                "F003 enabled • enabling encrypted F002 control notifications…"
                        )

                        enableNotify(
                            g,
                            f002
                                ?: return
                        )
                    }

                    AidexProtocol
                        .F002_UUID -> {
                        notifyStep =
                            NotifyStep.F001

                        update(
                            stage =
                                LiveGattStage.ENABLING_F001,
                            connected =
                                true,
                            message =
                                "F002 enabled • enabling F001 key notifications…"
                        )

                        enableNotify(
                            g,
                            f001
                                ?: return
                        )
                    }

                    AidexProtocol
                        .F001_UUID -> {
                        notifyStep =
                            NotifyStep.NONE

                        f001WriteCompleted =
                            false

                        f001MasterKeyReceived =
                            false

                        sessionKeyReadStarted =
                            false

                        sessionKeyReadRetryCount =
                            0

                        update(
                            stage =
                                LiveGattStage.REQUESTING_MASTER_KEY,
                            connected =
                                true,
                            message =
                                "Requesting fresh AiDEX master key…"
                        )

                        write(
                            g =
                                g,
                            characteristic =
                                f001
                                    ?: return,
                            value =
                                AidexCrypto
                                    .keyRequest(
                                        serial
                                    ),
                            writeType =
                                BluetoothGattCharacteristic
                                    .WRITE_TYPE_DEFAULT
                        )
                    }
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic,
                status: Int
            ) {
                touchTimeout()

                if (
                    characteristic.uuid ==
                    AidexProtocol
                        .F001_UUID
                ) {
                    if (
                        status !=
                        BluetoothGatt.GATT_SUCCESS
                    ) {
                        if (
                            isAuthenticationStatus(
                                status
                            )
                        ) {
                            requestSecureBond(
                                retryStep =
                                    BondRetryStep.WRITE_F001,
                                reason =
                                    "Sensor requested secure Bluetooth authentication for the F001 key exchange."
                            )
                            return
                        }

                        scheduleReconnect(
                            "F001 key-request write failed: $status"
                        )

                        return
                    }

                    f001WriteCompleted =
                        true

                    update(
                        stage =
                            LiveGattStage.REQUESTING_MASTER_KEY,
                        connected =
                            true,
                        message =
                            if (
                                f001MasterKeyReceived
                            ) {
                                "F001 write completed • reading encrypted session key…"
                            } else {
                                "F001 write completed • waiting for master-key notification…"
                            }
                    )

                    maybeStartSessionKeyRead(
                        g
                    )
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                touchTimeout()

                if (
                    characteristic.uuid ==
                    AidexProtocol.F003_UUID
                ) {
                    _state.value =
                        _state.value.copy(
                            f003LastReadStatus =
                                status,
                            f003LastSource =
                                "read",
                            f003LastRawAtMs =
                                if (
                                    status ==
                                    BluetoothGatt.GATT_SUCCESS
                                ) {
                                    System.currentTimeMillis()
                                } else {
                                    _state.value.f003LastRawAtMs
                                },
                            f003LastRawLength =
                                if (
                                    status ==
                                    BluetoothGatt.GATT_SUCCESS
                                ) {
                                    value.size
                                } else {
                                    _state.value.f003LastRawLength
                                }
                        )

                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    ) {
                        update(
                            stage =
                                LiveGattStage.WAITING_FOR_CURRENT,
                            connected =
                                true,
                            message =
                                "Read-only F003 value received (${value.size} bytes) • validating current glucose…",
                            error =
                                null
                        )

                        processF003(
                            value
                        )
                    } else {
                        update(
                            stage =
                                LiveGattStage.WAITING_FOR_CURRENT,
                            connected =
                                true,
                            message =
                                "F003 read attempt returned GATT status $status • notification path remains active.",
                            error =
                                null
                        )

                        touchCurrentTimeout()
                    }

                    return
                }

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    if (
                        characteristic.uuid ==
                        AidexProtocol.F002_UUID &&
                        isAuthenticationStatus(
                            status
                        )
                    ) {
                        sessionKeyReadStarted =
                            false

                        requestSecureBond(
                            retryStep =
                                BondRetryStep.READ_F002,
                            reason =
                                "Sensor requested secure Bluetooth authentication for the encrypted F002 session key."
                        )
                        return
                    }

                    scheduleReconnect(
                        "AiDEX characteristic read failed ${characteristic.uuid}: $status"
                    )

                    return
                }

                if (
                    characteristic.uuid ==
                    AidexProtocol
                        .F002_UUID
                ) {
                    val master =
                        key0
                            ?: return scheduleReconnect(
                                "F002 session key arrived before F001 master key."
                            )

                    val session =
                        AidexCrypto
                            .decodeSessionKey(
                                master,
                                iv,
                                value
                            )
                            ?: return scheduleReconnect(
                                "F002 KEY1 CRC8 validation failed."
                            )

                    key1 =
                        session

                    sessionKeyReadStarted =
                        false

                    protocolInitStep =
                        ProtocolInitStep.START_INFO

                    update(
                        stage =
                            LiveGattStage.INITIALIZING_PROTOCOL,
                        connected =
                            true,
                        message =
                            "Encrypted KEY1 established • sending AiDEX startup command…"
                    )

                    sendEncryptedF002(
                        AidexProtocol
                            .START_INFO_COMMAND
                    )
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                touchTimeout()

                when (
                    characteristic.uuid
                ) {
                    AidexProtocol
                        .F001_UUID -> {
                        if (
                            value.size !=
                            16
                        ) {
                            scheduleReconnect(
                                "Unexpected F001 master-key length ${value.size}."
                            )

                            return
                        }

                        key0 =
                            value.copyOf()

                        f001MasterKeyReceived =
                            true

                        update(
                            stage =
                                LiveGattStage.REQUESTING_MASTER_KEY,
                            connected =
                                true,
                            message =
                                if (
                                    f001WriteCompleted
                                ) {
                                    "Master key received • reading encrypted F002 session key…"
                                } else {
                                    "Master key received • waiting for F001 write completion…"
                                }
                        )

                        maybeStartSessionKeyRead(
                            g
                        )
                    }

                    AidexProtocol
                        .F002_UUID ->
                        processF002(
                            value
                        )

                    AidexProtocol
                        .F003_UUID -> {
                        _state.value =
                            _state.value.copy(
                                f003NotificationCount =
                                    _state.value.f003NotificationCount +
                                        1,
                                f003LastRawAtMs =
                                    System.currentTimeMillis(),
                                f003LastRawLength =
                                    value.size,
                                f003LastSource =
                                    "notify"
                            )

                        processF003(
                            value
                        )
                    }
                }
            }

            @Deprecated(
                "Deprecated in Java"
            )
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic,
                status: Int
            ) {
                onCharacteristicRead(
                    g,
                    characteristic,
                    characteristic.value
                        ?: byteArrayOf(),
                    status
                )
            }

            @Deprecated(
                "Deprecated in Java"
            )
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic
            ) {
                onCharacteristicChanged(
                    g,
                    characteristic,
                    characteristic.value
                        ?: byteArrayOf()
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun requestSecureBond(
        retryStep: BondRetryStep,
        reason: String
    ) {
        if (
            stopped
        ) {
            return
        }

        val target =
            device
                ?: return scheduleReconnect(
                    "Bluetooth device unavailable while secure bonding was requested."
                )

        waitingForBond =
            true

        bondRetryStep =
            retryStep

        handler.removeCallbacks(
            handshakeTimeoutRunnable
        )

        handler.removeCallbacks(
            currentTimeoutRunnable
        )

        update(
            stage =
                LiveGattStage.BONDING,
            connected =
                true,
            streaming =
                false,
            message =
                "Sensor requires secure Bluetooth bonding…",
            error =
                reason
        )

        when (
            target.bondState
        ) {
            BluetoothDevice.BOND_BONDED -> {
                /*
                 * Android can report ATT authentication even for an already
                 * bonded peer when the current LE link was not encrypted yet.
                 * Reconnect once so the bonded relationship is applied to the
                 * new GATT link instead of looping the same rejected request.
                 */
                waitingForBond =
                    false

                bondRetryStep =
                    BondRetryStep.NONE

                scheduleReconnect(
                    "Sensor is bonded; reopening GATT so Android can establish an encrypted link."
                )
            }

            BluetoothDevice.BOND_BONDING -> {
                // ACTION_BOND_STATE_CHANGED will resume the exact operation.
            }

            else -> {
                val started =
                    runCatching {
                        target.createBond()
                    }
                        .getOrDefault(
                            false
                        )

                if (
                    !started
                ) {
                    waitingForBond =
                        false

                    bondRetryStep =
                        BondRetryStep.NONE

                    scheduleReconnect(
                        "Android could not start LE Secure Connections bonding."
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retryAfterBond() {
        if (
            stopped
        ) {
            return
        }

        val g =
            gatt
                ?: return scheduleReconnect(
                    "GATT disconnected while Bluetooth bonding completed."
                )

        val step =
            bondRetryStep

        bondRetryStep =
            BondRetryStep.NONE

        when (
            step
        ) {
            BondRetryStep.ENABLE_F003 ->
                enableNotify(
                    g,
                    f003
                        ?: return scheduleReconnect(
                            "F003 missing after Bluetooth bonding."
                        )
                )

            BondRetryStep.ENABLE_F002 ->
                enableNotify(
                    g,
                    f002
                        ?: return scheduleReconnect(
                            "F002 missing after Bluetooth bonding."
                        )
                )

            BondRetryStep.ENABLE_F001 ->
                enableNotify(
                    g,
                    f001
                        ?: return scheduleReconnect(
                            "F001 missing after Bluetooth bonding."
                        )
                )

            BondRetryStep.WRITE_F001 ->
                write(
                    g =
                        g,
                    characteristic =
                        f001
                            ?: return scheduleReconnect(
                                "F001 missing after Bluetooth bonding."
                            ),
                    value =
                        AidexCrypto
                            .keyRequest(
                                serial
                            ),
                    writeType =
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_DEFAULT
                )

            BondRetryStep.READ_F002 -> {
                sessionKeyReadStarted =
                    false

                readSessionKey(
                    g
                )
            }

            BondRetryStep.NONE ->
                scheduleReconnect(
                    "Bluetooth bond completed with no pending secure GATT operation."
                )
        }

        touchTimeout()
    }

    private fun isAuthenticationStatus(
        status: Int
    ): Boolean =
        status ==
            GATT_INSUFFICIENT_AUTHENTICATION ||
            status ==
            GATT_INSUFFICIENT_ENCRYPTION

    private fun processF003(
        encrypted: ByteArray
    ) {
        val session =
            key1
                ?: return

        if (
            encrypted.size !=
            AidexF003Parser
                .CLEAR_PACKET_LENGTH
        ) {
            update(
                stage =
                    LiveGattStage.WAITING_FOR_CURRENT,
                connected =
                    true,
                message =
                    "F003 callback received with unexpected encrypted length ${encrypted.size} • waiting for another current packet.",
                error =
                    null
            )

            touchCurrentTimeout()

            return
        }

        val clear =
            AidexProtocol
                .decryptAndValidate(
                    session,
                    iv,
                    encrypted
                )
                ?: run {
                    update(
                        stage =
                            LiveGattStage.WAITING_FOR_CURRENT,
                        connected =
                            true,
                        message =
                            "F003 callback reached Android but failed AES/CRC16 validation • waiting for another current packet.",
                        lastCrcValid =
                            false,
                        error =
                            null
                    )

                    touchCurrentTimeout()

                    return
                }

        val reading =
            AidexF003Parser
                .parseClearWithCrc(
                    serial =
                        serial,
                    clearWithCrc =
                        clear
                )
                ?: run {
                    update(
                        stage =
                            LiveGattStage.WAITING_FOR_CURRENT,
                        connected =
                            true,
                        message =
                            "F003 decrypted and passed CRC16 but the current-reading payload could not be parsed • waiting for another packet.",
                        error =
                            null
                    )

                    touchCurrentTimeout()

                    return
                }

        handler.removeCallbacksAndMessages(
            null
        )

        protocolInitStep =
            ProtocolInitStep.READY

        update(
            stage =
                LiveGattStage.STREAMING,
            connected =
                true,
            streaming =
                true,
            lastPacketAtMs =
                System.currentTimeMillis(),
            lastSensorMinute =
                reading.sensorMinute,
            lastCrcValid =
                true,
            message =
                "Secure F003 glucose stream active • sensor minute ${reading.sensorMinute}",
            error =
                null
        )

        onReading(
            reading
        )
    }

    private fun processF002(
        encrypted: ByteArray
    ) {
        if (
            _state.value.streaming
        ) {
            return
        }

        val session =
            key1
                ?: return

        val clear =
            AidexProtocol
                .decryptAndValidate(
                    session,
                    iv,
                    encrypted
                )
                ?: run {
                    update(
                        stage =
                            LiveGattStage.INITIALIZING_PROTOCOL,
                        message =
                            "Encrypted F002 initialization response failed CRC16.",
                        error =
                            "F002 initialization CRC16 failed."
                    )

                    return
                }

        val type =
            AidexProtocol
                .responseType(
                    clear
                )
                ?: return

        _state.value =
            _state.value.copy(
                lastF002Response =
                    type,
                lastF002PayloadHex =
                    clear.joinToString("") {
                        byte ->
                        "%02X".format(
                            byte.toInt() and 0xFF
                        )
                    },
                protocolStep =
                    protocolInitStep.name
            )

        when (
            type
        ) {
            0x110,
            0x120,
            0x020 -> {
                protocolInitStep =
                    ProtocolInitStep.ASK_START_TIME

                update(
                    stage =
                        LiveGattStage.INITIALIZING_PROTOCOL,
                    connected =
                        true,
                    message =
                        "Sensor information received • requesting session start time…"
                )

                sendEncryptedF002(
                    AidexProtocol
                        .ASK_START_TIME_COMMAND
                )
            }

            0x121 -> {
                val startYear =
                    if (
                        clear.size >=
                        4
                    ) {
                        (clear[2].toInt() and 0xFF) or
                            (
                                (clear[3].toInt() and 0xFF) shl
                                    8
                            )
                    } else {
                        -1
                    }

                val activationState =
                    when {
                        startYear ==
                            0 ->
                            "UNINITIALIZED"

                        startYear in
                            2020..2100 ->
                            "INITIALIZED"

                        else ->
                            "UNKNOWN"
                    }

                _state.value =
                    _state.value.copy(
                        sensorActivationState =
                            activationState,
                        sensorStartYear =
                            startYear
                    )

                if (
                    activationState ==
                    "UNINITIALIZED"
                ) {
                    protocolInitStep =
                        ProtocolInitStep.READY

                    handler.removeCallbacks(
                        handshakeTimeoutRunnable
                    )

                    handler.removeCallbacks(
                        currentTimeoutRunnable
                    )

                    update(
                        stage =
                            LiveGattStage.ACTIVATION_REQUIRED,
                        connected =
                            true,
                        protocolStep =
                            ProtocolInitStep.READY.name,
                        sensorActivationState =
                            activationState,
                        sensorStartYear =
                            startYear,
                        message =
                            "Sensor reports no initialized start time • new-sensor activation is incomplete.",
                        error =
                            null
                    )

                    return
                }

                if (
                    protocolInitStep ==
                    ProtocolInitStep.VERIFY_START_TIME
                ) {
                    /*
                     * 0x111 is not terminal on a newly selected sensor. After
                     * re-reading the active start/session state with 0x21, use
                     * the same read-only 0x22 state probe as encrypted history.
                     * Do not request any history pages here.
                     */
                    protocolInitStep =
                        ProtocolInitStep.VERIFY_HISTORY_POSITION

                    update(
                        stage =
                            LiveGattStage.INITIALIZING_PROTOCOL,
                        connected =
                            true,
                        message =
                            "Active session confirmed • asking latest sensor history position (read-only)…"
                    )

                    sendEncryptedF002(
                        AidexHistoryProtocol
                            .ASK_LAST_ID_COMMAND
                    )
                } else {
                    /*
                     * Active/bonded sensors are already running. Complete the
                     * normal AiDEX finalize sequence rather than changing time.
                     */
                    protocolInitStep =
                        ProtocolInitStep.FINALIZE_35

                    update(
                        stage =
                            LiveGattStage.INITIALIZING_PROTOCOL,
                        connected =
                            true,
                        message =
                            "Session information received • finalizing live stream (35)…"
                    )

                    sendEncryptedF002(
                        AidexProtocol
                            .FINALIZE_35_COMMAND
                    )
                }
            }

            0x135 -> {
                protocolInitStep =
                    ProtocolInitStep.FINALIZE_34

                update(
                    stage =
                        LiveGattStage.INITIALIZING_PROTOCOL,
                    connected =
                        true,
                    message =
                        "AiDEX finalize 35 accepted • finalizing live stream (34)…"
                )

                sendEncryptedF002(
                    AidexProtocol
                        .FINALIZE_34_COMMAND
                )
            }

            0x134 -> {
                protocolInitStep =
                    ProtocolInitStep.FINALIZE_11

                update(
                    stage =
                        LiveGattStage.WAITING_FOR_CURRENT,
                    connected =
                        true,
                    message =
                        "AiDEX finalize 34 accepted • enabling current-glucose stream…"
                )

                sendEncryptedF002(
                    AidexProtocol
                        .FINALIZE_11_COMMAND
                )
            }

            0x111 -> {
                /*
                 * 0x111 is an intermediate response in both the proven new-
                 * sensor activation path and the encrypted-history read path.
                 * Continue with a read-only 0x21 state query instead of
                 * declaring initialization complete and waiting forever.
                 */
                protocolInitStep =
                    ProtocolInitStep.VERIFY_START_TIME

                update(
                    stage =
                        LiveGattStage.INITIALIZING_PROTOCOL,
                    connected =
                        true,
                    message =
                        "Finalize/current-state response 0x111 received • confirming active session state (0x21)…",
                    error =
                        null
                )

                sendEncryptedF002(
                    AidexProtocol
                        .ASK_START_TIME_COMMAND
                )
            }

            0x122 -> {
                val lastHistoryId =
                    AidexHistoryProtocol
                        .parseLastId(
                            clear
                        )

                protocolInitStep =
                    ProtocolInitStep.READY

                update(
                    stage =
                        LiveGattStage.ENABLING_F003,
                    connected =
                        true,
                    message =
                        if (
                            lastHistoryId !=
                            null
                        ) {
                            "Read-only sensor state confirmed • latest history ID $lastHistoryId • re-arming F003 current subscription…"
                        } else {
                            "Read-only sensor state response 0x122 received • re-arming F003 current subscription…"
                        },
                    error =
                        null
                )

                handler.postDelayed(
                    {
                        rearmF003Subscription()
                    },
                    F003_REARM_DELAY_MS
                )
            }

            else -> {
                /*
                 * Do not tear down a valid secure session solely because the
                 * active sensor returned an initialization response variant we
                 * have not yet mapped. F003 notifications are authoritative.
                 */
                update(
                    stage =
                        LiveGattStage.WAITING_FOR_CURRENT,
                    connected =
                        true,
                    message =
                        "Secure session established • F002 response 0x${type.toString(16).uppercase()} • waiting for F003 glucose…",
                    error =
                        null
                )

                touchCurrentTimeout()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun rearmF003Subscription() {
        if (
            stopped ||
            _state.value.streaming
        ) {
            return
        }

        val g =
            gatt
                ?: return scheduleReconnect(
                    "GATT unavailable while re-arming F003."
                )

        val characteristic =
            f003
                ?: return scheduleReconnect(
                    "F003 unavailable while re-arming current notifications."
                )

        rearmingF003 =
            true

        update(
            stage =
                LiveGattStage.ENABLING_F003,
            connected =
                true,
            message =
                "Re-arming F003 CCCD after secure sensor-state confirmation…",
            error =
                null
        )

        enableNotify(
            g,
            characteristic
        )
    }

    @SuppressLint("MissingPermission")
    private fun attemptF003ReadIfSupported() {
        if (
            stopped ||
            _state.value.streaming
        ) {
            return
        }

        val characteristic =
            f003
                ?: return

        val properties =
            characteristic.properties

        val readSupported =
            (
                properties and
                    BluetoothGattCharacteristic.PROPERTY_READ
                ) !=
                0

        _state.value =
            _state.value.copy(
                f003Properties =
                    properties,
                f003ReadSupported =
                    readSupported
            )

        if (
            !readSupported
        ) {
            update(
                stage =
                    LiveGattStage.WAITING_FOR_CURRENT,
                connected =
                    true,
                message =
                    "F003 is notify-only on this sensor • subscription is armed and AiDEX X is waiting for the next current glucose notification.",
                error =
                    null
            )

            touchCurrentTimeout()

            return
        }

        val g =
            gatt
                ?: return

        val attempt =
            _state.value.f003ReadAttempts +
                1

        _state.value =
            _state.value.copy(
                f003ReadAttempts =
                    attempt
            )

        val started =
            runCatching {
                g.readCharacteristic(
                    characteristic
                )
            }
                .getOrDefault(
                    false
                )

        if (
            started
        ) {
            f003ReadRetryCount =
                0

            update(
                stage =
                    LiveGattStage.WAITING_FOR_CURRENT,
                connected =
                    true,
                message =
                    "F003 subscription armed • requesting a read-only current value (attempt $attempt)…",
                error =
                    null
            )

            touchCurrentTimeout()

            return
        }

        _state.value =
            _state.value.copy(
                f003LastReadStatus =
                    F003_READ_START_REJECTED
            )

        f003ReadRetryCount +=
            1

        if (
            f003ReadRetryCount <=
            F003_READ_MAX_RETRIES
        ) {
            update(
                stage =
                    LiveGattStage.WAITING_FOR_CURRENT,
                connected =
                    true,
                message =
                    "Android GATT was busy starting the optional F003 read • retrying ${f003ReadRetryCount}/$F003_READ_MAX_RETRIES while notifications remain active.",
                error =
                    null
            )

            handler.postDelayed(
                {
                    attemptF003ReadIfSupported()
                },
                F003_READ_RETRY_DELAY_MS
            )

            return
        }

        update(
            stage =
                LiveGattStage.WAITING_FOR_CURRENT,
            connected =
                true,
            message =
                "F003 notification subscription is armed; optional read could not start, so AiDEX X is waiting for the next notification.",
            error =
                null
        )

        touchCurrentTimeout()
    }

    private fun maybeStartSessionKeyRead(
        g: BluetoothGatt
    ) {
        if (
            sessionKeyReadStarted ||
            !f001WriteCompleted ||
            !f001MasterKeyReceived ||
            key0 ==
            null
        ) {
            return
        }

        readSessionKey(
            g
        )
    }

    @SuppressLint("MissingPermission")
    private fun readSessionKey(
        g: BluetoothGatt
    ) {
        if (
            sessionKeyReadStarted
        ) {
            return
        }

        val characteristic =
            f002
                ?: return scheduleReconnect(
                    "F002 missing before session-key read."
                )

        val started =
            g.readCharacteristic(
                characteristic
            )

        if (
            started
        ) {
            sessionKeyReadStarted =
                true

            sessionKeyReadRetryCount =
                0

            update(
                stage =
                    LiveGattStage.READING_SESSION_KEY,
                connected =
                    true,
                message =
                    "Encrypted F002 session-key read started…"
            )

            touchTimeout()

            return
        }

        sessionKeyReadRetryCount +=
            1

        if (
            sessionKeyReadRetryCount >
            SESSION_KEY_READ_MAX_RETRIES
        ) {
            scheduleReconnect(
                "Android GATT remained busy and could not read F002 KEY1."
            )

            return
        }

        update(
            stage =
                LiveGattStage.READING_SESSION_KEY,
            connected =
                true,
            message =
                "Android GATT busy • retrying KEY1 read ${sessionKeyReadRetryCount}/$SESSION_KEY_READ_MAX_RETRIES…"
        )

        handler.postDelayed(
            {
                if (
                    !stopped &&
                    !sessionKeyReadStarted
                ) {
                    readSessionKey(
                        g
                    )
                }
            },
            SESSION_KEY_READ_RETRY_MS
        )
    }

    private fun sendEncryptedF002(
        clearWithCrc: ByteArray
    ) {
        val session =
            key1
                ?: return scheduleReconnect(
                    "KEY1 unavailable before encrypted F002 write."
                )

        val g =
            gatt
                ?: return scheduleReconnect(
                    "GATT unavailable before encrypted F002 write."
                )

        val characteristic =
            f002
                ?: return scheduleReconnect(
                    "F002 unavailable before encrypted write."
                )

        val encrypted =
            AidexProtocol
                .encryptedCommand(
                    session,
                    iv,
                    clearWithCrc
                )

        write(
            g =
                g,
            characteristic =
                characteristic,
            value =
                encrypted,
            writeType =
                BluetoothGattCharacteristic
                    .WRITE_TYPE_NO_RESPONSE
        )

        touchTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(
        g: BluetoothGatt,
        characteristic:
            BluetoothGattCharacteristic
    ) {
        val localEnabled =
            g.setCharacteristicNotification(
                characteristic,
                true
            )

        if (
            characteristic.uuid ==
            AidexProtocol.F003_UUID
        ) {
            _state.value =
                _state.value.copy(
                    f003LocalNotifyEnabled =
                        localEnabled
                )
        }

        if (
            !localEnabled
        ) {
            scheduleReconnect(
                "Could not enable local notification ${characteristic.uuid}."
            )

            return
        }

        val cccd =
            characteristic
                .getDescriptor(
                    AidexProtocol
                        .CCCD_UUID
                )
                    ?: return scheduleReconnect(
                        "CCCD missing for ${characteristic.uuid}."
                    )

        if (
            Build.VERSION.SDK_INT >=
            33
        ) {
            val result =
                g.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
                )

            if (
                characteristic.uuid ==
                AidexProtocol.F003_UUID &&
                result !=
                BluetoothStatusCodes.SUCCESS
            ) {
                _state.value =
                    _state.value.copy(
                        f003CccdWriteStatus =
                            result
                    )
            }

            if (
                result !=
                BluetoothStatusCodes.SUCCESS
            ) {
                scheduleReconnect(
                    "CCCD write rejected for ${characteristic.uuid}: $result"
                )
            }
        } else {
            @Suppress(
                "DEPRECATION"
            )
            cccd.value =
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE

            @Suppress(
                "DEPRECATION"
            )
            if (
                !g.writeDescriptor(
                    cccd
                )
            ) {
                if (
                    characteristic.uuid ==
                    AidexProtocol.F003_UUID
                ) {
                    _state.value =
                        _state.value.copy(
                            f003CccdWriteStatus =
                                F003_CCCD_START_REJECTED
                        )
                }

                scheduleReconnect(
                    "CCCD write could not start for ${characteristic.uuid}."
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(
        g: BluetoothGatt,
        characteristic:
            BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ) {
        if (
            Build.VERSION.SDK_INT >=
            33
        ) {
            val status =
                g.writeCharacteristic(
                    characteristic,
                    value,
                    writeType
                )

            if (
                status !=
                BluetoothStatusCodes.SUCCESS
            ) {
                scheduleReconnect(
                    "AiDEX GATT write rejected ${characteristic.uuid}: $status"
                )
            }
        } else {
            @Suppress(
                "DEPRECATION"
            )
            characteristic.writeType =
                writeType

            @Suppress(
                "DEPRECATION"
            )
            characteristic.value =
                value

            @Suppress(
                "DEPRECATION"
            )
            if (
                !g.writeCharacteristic(
                    characteristic
                )
            ) {
                scheduleReconnect(
                    "AiDEX GATT write could not start ${characteristic.uuid}."
                )
            }
        }
    }

    private fun touchTimeout() {
        handler.removeCallbacks(
            handshakeTimeoutRunnable
        )

        handler.postDelayed(
            handshakeTimeoutRunnable,
            HANDSHAKE_TIMEOUT_MS
        )
    }

    private fun touchCurrentTimeout() {
        handler.removeCallbacks(
            handshakeTimeoutRunnable
        )

        handler.removeCallbacks(
            currentTimeoutRunnable
        )

        handler.postDelayed(
            currentTimeoutRunnable,
            CURRENT_FIRST_PACKET_TIMEOUT_MS
        )
    }

    private val handshakeTimeoutRunnable =
        Runnable {
            if (
                !stopped &&
                !_state.value.streaming
            ) {
                scheduleReconnect(
                    "Secure AiDEX handshake timed out at ${_state.value.stage}."
                )
            }
        }

    private val currentTimeoutRunnable =
        Runnable {
            if (
                !stopped &&
                !_state.value.streaming
            ) {
                scheduleReconnect(
                    "Secure session established but no F003 glucose packet arrived."
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val current =
            gatt

        gatt =
            null

        if (
            current !=
            null
        ) {
            runCatching {
                current.disconnect()
            }

            runCatching {
                current.close()
            }
        }
    }

    private fun scheduleReconnect(
        reason: String
    ) {
        if (
            stopped
        ) {
            return
        }

        handler.removeCallbacksAndMessages(
            null
        )

        closeGatt()

        resetSession()

        reconnectCount +=
            1

        update(
            stage =
                LiveGattStage.RETRY_WAIT,
            connected =
                false,
            streaming =
                false,
            lastPacketAtMs =
                0L,
            lastSensorMinute =
                -1,
            lastCrcValid =
                null,
            message =
                "Secure live GATT retry in 5 sec…",
            error =
                reason,
            reconnectCount =
                reconnectCount
        )

        handler.postDelayed(
            reconnectRunnable,
            RECONNECT_DELAY_MS
        )
    }

    private fun update(
        stage: LiveGattStage,
        connected: Boolean =
            _state.value.connected,
        streaming: Boolean =
            _state.value.streaming,
        lastPacketAtMs: Long =
            _state.value.lastPacketAtMs,
        lastSensorMinute: Int =
            _state.value.lastSensorMinute,
        lastCrcValid: Boolean? =
            _state.value.lastCrcValid,
        reconnectCount: Int =
            _state.value.reconnectCount,
        protocolStep: String? =
            protocolInitStep.name,
        lastF002Response: Int =
            _state.value.lastF002Response,
        lastF002PayloadHex: String? =
            _state.value.lastF002PayloadHex,
        sensorActivationState: String =
            _state.value.sensorActivationState,
        sensorStartYear: Int =
            _state.value.sensorStartYear,
        f003Properties: Int =
            _state.value.f003Properties,
        f003LocalNotifyEnabled: Boolean? =
            _state.value.f003LocalNotifyEnabled,
        f003CccdWriteStatus: Int =
            _state.value.f003CccdWriteStatus,
        f003CccdWriteCount: Int =
            _state.value.f003CccdWriteCount,
        f003NotificationCount: Int =
            _state.value.f003NotificationCount,
        f003LastRawAtMs: Long =
            _state.value.f003LastRawAtMs,
        f003LastRawLength: Int =
            _state.value.f003LastRawLength,
        f003LastSource: String? =
            _state.value.f003LastSource,
        f003ReadSupported: Boolean =
            _state.value.f003ReadSupported,
        f003ReadAttempts: Int =
            _state.value.f003ReadAttempts,
        f003LastReadStatus: Int =
            _state.value.f003LastReadStatus,
        message: String =
            _state.value.message,
        error: String? =
            _state.value.error
    ) {
        _state.value =
            LiveGattState(
                stage =
                    stage,
                connected =
                    connected,
                streaming =
                    streaming,
                lastPacketAtMs =
                    lastPacketAtMs,
                lastSensorMinute =
                    lastSensorMinute,
                lastCrcValid =
                    lastCrcValid,
                reconnectCount =
                    reconnectCount,
                protocolStep =
                    protocolStep,
                lastF002Response =
                    lastF002Response,
                lastF002PayloadHex =
                    lastF002PayloadHex,
                sensorActivationState =
                    sensorActivationState,
                sensorStartYear =
                    sensorStartYear,
                f003Properties =
                    f003Properties,
                f003LocalNotifyEnabled =
                    f003LocalNotifyEnabled,
                f003CccdWriteStatus =
                    f003CccdWriteStatus,
                f003CccdWriteCount =
                    f003CccdWriteCount,
                f003NotificationCount =
                    f003NotificationCount,
                f003LastRawAtMs =
                    f003LastRawAtMs,
                f003LastRawLength =
                    f003LastRawLength,
                f003LastSource =
                    f003LastSource,
                f003ReadSupported =
                    f003ReadSupported,
                f003ReadAttempts =
                    f003ReadAttempts,
                f003LastReadStatus =
                    f003LastReadStatus,
                message =
                    message,
                error =
                    error
            )
    }

    private fun hasConnectPermission():
        Boolean =
        Build.VERSION.SDK_INT <
            31 ||
            ContextCompat
                .checkSelfPermission(
                    appContext,
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                ) ==
            PackageManager
                .PERMISSION_GRANTED

    companion object {
        private const val RECONNECT_DELAY_MS =
            5_000L

        private const val HANDSHAKE_TIMEOUT_MS =
            20_000L

        private const val CURRENT_FIRST_PACKET_TIMEOUT_MS =
            90_000L

        private const val SESSION_KEY_READ_RETRY_MS =
            120L

        private const val SESSION_KEY_READ_MAX_RETRIES =
            8

        private const val BOND_RETRY_DELAY_MS =
            350L

        private const val F003_REARM_DELAY_MS =
            100L

        private const val F003_READ_AFTER_REARM_DELAY_MS =
            120L

        private const val F003_READ_RETRY_DELAY_MS =
            250L

        private const val F003_READ_MAX_RETRIES =
            2

        private const val F003_READ_START_REJECTED =
            -2

        private const val F003_CCCD_START_REJECTED =
            -2

        private const val GATT_INSUFFICIENT_AUTHENTICATION =
            5

        private const val GATT_INSUFFICIENT_ENCRYPTION =
            15
    }
}
