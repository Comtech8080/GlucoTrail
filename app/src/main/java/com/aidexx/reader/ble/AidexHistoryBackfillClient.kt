package com.aidexx.reader.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.aidexx.reader.protocol.AidexCrypto
import com.aidexx.reader.protocol.AidexHistoryProtocol
import com.aidexx.reader.protocol.AidexProtocol
import com.aidexx.reader.protocol.AidexReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

enum class HistoryBackfillStage {
    IDLE,
    CONNECTING,
    DISCOVERING,
    CONFIGURING,
    REQUESTING,
    RECEIVING,
    COMPLETE,
    FAILED
}

data class HistoryBackfillState(
    val stage: HistoryBackfillStage =
        HistoryBackfillStage.IDLE,
    val message: String =
        "History backfill idle",
    val requestedFromMinute: Int? =
        null,
    val recordsReceived: Int =
        0,
    val recordsPrepared: Int =
        0,
    val lastCompletedAtMs: Long? =
        null,
    val error: String? =
        null,
    val log: List<String> =
        emptyList()
)

/**
 * AiDEX X stored-history client.
 *
 * Important: although the device exposes the standard Bluetooth CGM service,
 * AiDEX X stored history is transferred through the proprietary encrypted
 * F002 characteristic.
 *
 * Read-only sequence used here:
 *
 * F003 notify
 * F002 notify
 * F001 notify
 * F001 key request -> KEY0
 * F002 read        -> encrypted KEY1
 * encrypted 0x10  -> device info
 * encrypted 0x21  -> session/start-time info
 * encrypted 0x22  -> last history position
 * encrypted 0x23  -> paged packed historical glucose
 *
 * This client never sends the activation/time-write/finalization commands.
 */
class AidexHistoryBackfillClient(
    private val context: Context
) {
    private val _state =
        MutableStateFlow(
            HistoryBackfillState()
        )

    val state:
        StateFlow<HistoryBackfillState> =
        _state

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

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

    private var iv =
        byteArrayOf()

    private var key0:
        ByteArray? =
        null

    private var key1:
        ByteArray? =
        null

    private var anchor:
        AidexReading? =
        null

    private var requestedHours =
        72

    private var requestedRelativeStart =
        1

    private var lastOnSensor =
        0

    private var relativeToSensorShift =
        0

    private var nextRelativeId =
        1

    private var closing =
        false

    private var completion:
        ((List<AidexReading>) -> Unit)? =
        null

    private val accumulated =
        linkedMapOf<Int, AidexReading>()

    private enum class NotifyStep {
        NONE,
        F003,
        F002,
        F001
    }

    private var notifyStep =
        NotifyStep.NONE

    /*
     * Android BluetoothGatt only allows one attribute operation at a time.
     * AiDEX can notify F001 with KEY0 before Android has delivered the
     * onCharacteristicWrite callback for the F001 key request. Starting the
     * F002 read from that notification races the still-busy GATT operation and
     * readCharacteristic() returns false.
     */
    private var f001WriteCompleted =
        false

    private var f001MasterKeyReceived =
        false

    private var sessionKeyReadStarted =
        false

    private var sessionKeyReadRetryCount =
        0

    @SuppressLint("MissingPermission")
    fun start(
        device: BluetoothDevice,
        serial: String,
        anchor: AidexReading,
        hours: Int =
            72,
        onComplete:
            (List<AidexReading>) -> Unit
    ): Boolean {
        if (
            !acquireGlobalSlot()
        ) {
            update(
                HistoryBackfillStage.FAILED,
                "Another AiDEX history sync is already active."
            )
            return false
        }

        if (
            _state.value.stage in
            ACTIVE_STAGES
        ) {
            releaseGlobalSlot()
            return false
        }

        if (
            !hasConnectPermission()
        ) {
            fail(
                "Bluetooth connect permission is not granted"
            )
            return false
        }

        val normalized =
            serial.trim().uppercase()

        if (
            !AidexProtocol
                .isValidSerial(
                    normalized
                )
        ) {
            fail(
                "AiDEX serial is invalid"
            )
            return false
        }

        cancelInternal(
            resetState =
                false,
            releaseSlot =
                false
        )

        this.device =
            device

        this.serial =
            normalized

        this.anchor =
            anchor

        this.requestedHours =
            hours
                .coerceIn(
                    1,
                    72
                )

        this.iv =
            AidexCrypto
                .serialIv(
                    normalized
                )

        this.key0 =
            null

        this.key1 =
            null

        this.f001WriteCompleted =
            false

        this.f001MasterKeyReceived =
            false

        this.sessionKeyReadStarted =
            false

        this.sessionKeyReadRetryCount =
            0

        this.lastOnSensor =
            0

        this.relativeToSensorShift =
            0

        this.requestedRelativeStart =
            1

        this.nextRelativeId =
            1

        this.closing =
            false

        this.completion =
            onComplete

        accumulated.clear()

        _state.value =
            HistoryBackfillState()

        update(
            stage =
                HistoryBackfillStage.CONNECTING,
            message =
                "Connecting to AiDEX encrypted F002 history channel."
        )

        return try {
            gatt =
                device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice
                        .TRANSPORT_LE
                )

            touchTimeout()

            true
        } catch (
            e: Exception
        ) {
            fail(
                "History connection failed: ${e.javaClass.simpleName}: ${e.message}"
            )

            false
        }
    }

    fun reportBlocked(
        message: String
    ) {
        if (
            _state.value.stage in
            ACTIVE_STAGES
        ) {
            return
        }

        _state.value =
            HistoryBackfillState(
                stage =
                    HistoryBackfillStage
                        .FAILED,
                message =
                    message,
                error =
                    message,
                log =
                    listOf(
                        message
                    )
            )
    }

    fun cancel(
        resetState: Boolean =
            true
    ) {
        cancelInternal(
            resetState =
                resetState,
            releaseSlot =
                true
        )
    }

    private fun cancelInternal(
        resetState: Boolean,
        releaseSlot: Boolean
    ) {
        handler
            .removeCallbacksAndMessages(
                null
            )

        closing =
            true

        closeGatt()

        accumulated.clear()

        completion =
            null

        anchor =
            null

        key0 =
            null

        key1 =
            null

        if (
            resetState
        ) {
            _state.value =
                HistoryBackfillState()
        }

        if (
            releaseSlot
        ) {
            releaseGlobalSlot()
        }
    }

    @SuppressLint("MissingPermission")
    private val callback =
        object :
            BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (
                    newState ==
                    BluetoothProfile
                        .STATE_CONNECTED &&
                    status ==
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    closing =
                        false

                    update(
                        HistoryBackfillStage
                            .DISCOVERING,
                        "Connected. Discovering AiDEX private CGM characteristics."
                    )

                    touchTimeout()

                    if (
                        !g.discoverServices()
                    ) {
                        fail(
                            "Could not start AiDEX service discovery"
                        )
                    }

                    return
                }

                if (
                    newState ==
                    BluetoothProfile
                        .STATE_DISCONNECTED
                ) {
                    runCatching {
                        g.close()
                    }

                    if (
                        !closing &&
                        _state.value.stage in
                        ACTIVE_STAGES
                    ) {
                        fail(
                            "History GATT disconnected with status $status"
                        )
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int
            ) {
                if (
                    status !=
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    fail(
                        "AiDEX service discovery failed: $status"
                    )
                    return
                }

                val service =
                    g.getService(
                        AidexProtocol
                            .SERVICE_UUID
                    )
                        ?: return fail(
                            "CGM service 0x181F not found"
                        )

                f001 =
                    service
                        .getCharacteristic(
                            AidexProtocol
                                .F001_UUID
                        )

                f002 =
                    service
                        .getCharacteristic(
                            AidexProtocol
                                .F002_UUID
                        )

                f003 =
                    service
                        .getCharacteristic(
                            AidexProtocol
                                .F003_UUID
                        )

                if (
                    f001 == null ||
                    f002 == null ||
                    f003 == null
                ) {
                    fail(
                        "Required AiDEX F001/F002/F003 characteristics are missing"
                    )
                    return
                }

                update(
                    HistoryBackfillStage
                        .CONFIGURING,
                    "Configuring encrypted AiDEX history notifications."
                )

                notifyStep =
                    NotifyStep.F003

                enableNotify(
                    g,
                    f003
                        ?: return fail(
                            "F003 missing"
                        )
                )
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor:
                    BluetoothGattDescriptor,
                status: Int
            ) {
                touchTimeout()

                if (
                    status !=
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    fail(
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
                        notifyStep =
                            NotifyStep.F002

                        enableNotify(
                            g,
                            f002
                                ?: return fail(
                                    "F002 missing"
                                )
                        )
                    }

                    AidexProtocol
                        .F002_UUID -> {
                        notifyStep =
                            NotifyStep.F001

                        enableNotify(
                            g,
                            f001
                                ?: return fail(
                                    "F001 missing"
                                )
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
                            HistoryBackfillStage
                                .CONFIGURING,
                            "Requesting fresh AiDEX master key."
                        )

                        write(
                            g =
                                g,
                            characteristic =
                                f001
                                    ?: return fail(
                                        "F001 missing"
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
                        BluetoothGatt
                            .GATT_SUCCESS
                    ) {
                        fail(
                            "F001 key-request write failed with GATT status $status"
                        )
                        return
                    }

                    f001WriteCompleted =
                        true

                    update(
                        HistoryBackfillStage
                            .CONFIGURING,
                        if (
                            f001MasterKeyReceived
                        ) {
                            "F001 key request write completed. Starting encrypted F002 session-key read."
                        } else {
                            "F001 key request write completed. Waiting for master-key notification."
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
                    status !=
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    fail(
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
                            ?: return fail(
                                "F002 session key arrived before F001 master key"
                            )

                    val session =
                        AidexCrypto
                            .decodeSessionKey(
                                master,
                                iv,
                                value
                            )
                            ?: return fail(
                                "AiDEX F002 session-key CRC8 validation failed"
                            )

                    key1 =
                        session

                    sessionKeyReadStarted =
                        false

                    update(
                        HistoryBackfillStage
                            .REQUESTING,
                        "Encrypted session established. Requesting sensor information."
                    )

                    sendEncrypted(
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
                            fail(
                                "Unexpected F001 master-key length ${value.size}"
                            )
                            return
                        }

                        key0 =
                            value.copyOf()

                        f001MasterKeyReceived =
                            true

                        update(
                            HistoryBackfillStage
                                .CONFIGURING,
                            if (
                                f001WriteCompleted
                            ) {
                                "AiDEX master key received. Starting encrypted F002 session-key read."
                            } else {
                                "AiDEX master key received. Waiting for F001 key-request write completion."
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
                        /*
                         * Current data can arrive while the temporary history
                         * connection is active. Live glucose remains handled by
                         * the normal advertisement/scanner path.
                         */
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

    private fun maybeStartSessionKeyRead(
        g: BluetoothGatt
    ) {
        if (
            sessionKeyReadStarted ||
            !f001WriteCompleted ||
            !f001MasterKeyReceived ||
            key0 == null
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

        if (
            key0 == null
        ) {
            fail(
                "Master key unavailable before F002 read"
            )
            return
        }

        val characteristic =
            f002
                ?: return fail(
                    "F002 missing"
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
                HistoryBackfillStage
                    .CONFIGURING,
                "Encrypted F002 session-key read started."
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
            fail(
                "Android GATT remained busy and could not start the encrypted F002 session-key read after $SESSION_KEY_READ_MAX_RETRIES retries"
            )
            return
        }

        update(
            HistoryBackfillStage
                .CONFIGURING,
            "Android GATT busy after F001 write. Retrying F002 session-key read (${sessionKeyReadRetryCount}/$SESSION_KEY_READ_MAX_RETRIES)."
        )

        handler.postDelayed(
            {
                if (
                    _state.value.stage in
                    ACTIVE_STAGES &&
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

    private fun processF002(
        encrypted: ByteArray
    ) {
        val session =
            key1
                ?: return fail(
                    "Encrypted F002 response arrived before session key"
                )

        val clear =
            AidexProtocol
                .decryptAndValidate(
                    session,
                    iv,
                    encrypted
                )
                ?: return fail(
                    "Encrypted F002 history response failed CRC16 validation"
                )

        val type =
            AidexProtocol
                .responseType(
                    clear
                )
                ?: return fail(
                    "Encrypted F002 history response is too short"
                )

        when (
            type
        ) {
            0x110,
            0x111,
            0x120,
            0x020 -> {
                update(
                    HistoryBackfillStage
                        .REQUESTING,
                    "Sensor information received. Reading session start information."
                )

                sendEncrypted(
                    AidexProtocol
                        .ASK_START_TIME_COMMAND
                )
            }

            0x121 -> {
                /*
                 * This is read-only. Do not adjust the sensor's clock during
                 * history recovery. Proceed directly to asking the sensor for
                 * its latest available history position.
                 */
                update(
                    HistoryBackfillStage
                        .REQUESTING,
                    "Session information received. Asking for latest history position."
                )

                sendEncrypted(
                    AidexHistoryProtocol
                        .ASK_LAST_ID_COMMAND
                )
            }

            0x122 -> {
                val last =
                    AidexHistoryProtocol
                        .parseLastId(
                            clear
                        )
                            ?: return fail(
                                "AiDEX 0x122 last-history response had an unexpected layout"
                            )

                lastOnSensor =
                    last

                val currentAnchor =
                    anchor
                        ?: return fail(
                            "History anchor reading disappeared"
                        )

                /*
                 * Align the sensor's proprietary relative history ID with the
                 * sensorMinute counter decoded from current advertisements.
                 */
                relativeToSensorShift =
                    currentAnchor
                        .sensorMinute -
                        lastOnSensor

                requestedRelativeStart =
                    (
                        lastOnSensor -
                            requestedHours *
                            60 +
                            1
                    )
                        .coerceAtLeast(
                            1
                        )

                nextRelativeId =
                    requestedRelativeStart

                val alignedStart =
                    requestedRelativeStart +
                        relativeToSensorShift

                update(
                    stage =
                        HistoryBackfillStage
                            .REQUESTING,
                    message =
                        "Sensor has history through minute ${currentAnchor.sensorMinute}. Downloading ${requestedHours}h from minute ${alignedStart.coerceAtLeast(0)}.",
                    requestedFromMinute =
                        alignedStart
                            .coerceAtLeast(
                                0
                            )
                )

                requestHistoryPage(
                    requestedRelativeStart
                )
            }

            0x123 -> {
                val batch =
                    AidexHistoryProtocol
                        .parseHistoryBatch(
                            clear
                        )
                            ?: return fail(
                                "AiDEX 0x123 history packet had an unexpected layout"
                            )

                processHistoryBatch(
                    batch
                )
            }

            else -> {
                fail(
                    "Unexpected encrypted AiDEX history response 0x${type.toString(16).uppercase().padStart(3, '0')}"
                )
            }
        }
    }

    private fun processHistoryBatch(
        batch:
            com.aidexx.reader.protocol.AidexHistoryBatch
    ) {
        val currentAnchor =
            anchor
                ?: return fail(
                    "History anchor reading disappeared"
                )

        val sessionStartMs =
            currentAnchor
                .receivedAtMs -
                currentAnchor
                    .sensorMinute
                    .toLong() *
                60_000L

        val now =
            System.currentTimeMillis()

        batch.values
            .forEach {
                    value ->
                val sensorMinute =
                    value.relativeId +
                        relativeToSensorShift

                if (
                    sensorMinute < 0 ||
                    sensorMinute >
                    currentAnchor
                        .sensorMinute +
                        5
                ) {
                    return@forEach
                }

                if (
                    !value.valid ||
                    value.warmup ||
                    value.glucoseMgDl <
                    MIN_GLUCOSE_MGDL ||
                    value.glucoseMgDl >
                    MAX_GLUCOSE_MGDL
                ) {
                    return@forEach
                }

                val receivedAtMs =
                    (
                        sessionStartMs +
                            sensorMinute
                                .toLong() *
                            60_000L
                    )
                        .coerceAtMost(
                            now
                        )

                accumulated[
                    sensorMinute
                ] =
                    AidexReading(
                        serial =
                            serial,
                        sensorMinute =
                            sensorMinute,
                        glucoseMgDl =
                            value.glucoseMgDl,
                        trendRaw =
                            0,
                        quality =
                            currentAnchor
                                .quality,
                        valid =
                            true,
                        previousMgDl =
                            null,
                        previous2MgDl =
                            null,
                        crcValid =
                            true,
                        rssi =
                            currentAnchor
                                .rssi,
                        receivedAtMs =
                            receivedAtMs
                    )
            }

        val next =
            batch.startRelativeId +
                batch.values.size

        nextRelativeId =
            next

        val requestedCount =
            (
                lastOnSensor -
                    requestedRelativeStart
            )
                .coerceAtLeast(
                    1
                )

        val progress =
            (
                next -
                    requestedRelativeStart
            )
                .coerceAtLeast(
                    0
                )

        update(
            stage =
                HistoryBackfillStage
                    .RECEIVING,
            message =
                "Receiving encrypted AiDEX history ${progress.coerceAtMost(requestedCount)}/$requestedCount minutes.",
            recordsReceived =
                accumulated.size,
            recordsPrepared =
                accumulated.size
        )

        /*
         * Juggluco continues with the next 0x23 request while the next relative
         * ID is still below the sensor's last-history position.
         */
        if (
            batch.values.isNotEmpty() &&
            next <
            lastOnSensor
        ) {
            requestHistoryPage(
                next
            )
        } else {
            finishSuccess()
        }
    }

    private fun requestHistoryPage(
        relativeId: Int
    ) {
        if (
            relativeId <= 0
        ) {
            finishSuccess()
            return
        }

        sendEncrypted(
            AidexHistoryProtocol
                .makeHistoryCommand(
                    relativeId
                )
        )
    }

    private fun finishSuccess() {
        handler
            .removeCallbacksAndMessages(
                null
            )

        val prepared =
            accumulated
                .values
                .sortedBy {
                    it.sensorMinute
                }

        update(
            stage =
                HistoryBackfillStage
                    .COMPLETE,
            message =
                if (
                    prepared.isEmpty()
                ) {
                    "Encrypted AiDEX history sync completed, but no additional valid stored readings were returned."
                } else {
                    "Encrypted AiDEX history sync completed with ${prepared.size} readings."
                },
            recordsReceived =
                prepared.size,
            recordsPrepared =
                prepared.size,
            lastCompletedAtMs =
                System.currentTimeMillis()
        )

        val callback =
            completion

        completion =
            null

        closing =
            true

        closeGatt()

        releaseGlobalSlot()

        callback?.invoke(
            prepared
        )
    }

    private fun sendEncrypted(
        clearWithCrc:
            ByteArray
    ) {
        val session =
            key1
                ?: return fail(
                    "AiDEX history session key unavailable"
                )

        val g =
            gatt
                ?: return fail(
                    "History GATT unavailable"
                )

        val characteristic =
            f002
                ?: return fail(
                    "F002 unavailable"
                )

        val encrypted =
            AidexProtocol
                .encryptedCommand(
                    session,
                    iv,
                    clearWithCrc
                )

        touchTimeout()

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
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(
        g: BluetoothGatt,
        characteristic:
            BluetoothGattCharacteristic
    ) {
        if (
            !g.setCharacteristicNotification(
                characteristic,
                true
            )
        ) {
            fail(
                "Could not enable notifications for ${characteristic.uuid}"
            )
            return
        }

        val cccd =
            characteristic
                .getDescriptor(
                    AidexProtocol
                        .CCCD_UUID
                )
                    ?: return fail(
                        "CCCD missing for ${characteristic.uuid}"
                    )

        if (
            Build.VERSION.SDK_INT >=
            33
        ) {
            val status =
                g.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
                )

            if (
                status !=
                BluetoothStatusCodes
                    .SUCCESS
            ) {
                fail(
                    "AiDEX CCCD write rejected: $status"
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
                fail(
                    "AiDEX CCCD write could not start"
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
                BluetoothStatusCodes
                    .SUCCESS
            ) {
                fail(
                    "AiDEX history write rejected: $status"
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
                fail(
                    "AiDEX history write could not start"
                )
            }
        }
    }

    private fun touchTimeout() {
        handler
            .removeCallbacksAndMessages(
                null
            )

        handler.postDelayed(
            {
                if (
                    _state.value.stage in
                    ACTIVE_STAGES
                ) {
                    fail(
                        "Encrypted AiDEX history sync timed out"
                    )
                }
            },
            HISTORY_TIMEOUT_MS
        )
    }

    private fun update(
        stage:
            HistoryBackfillStage,
        message:
            String,
        requestedFromMinute:
            Int? =
            _state.value
                .requestedFromMinute,
        recordsReceived:
            Int =
            _state.value
                .recordsReceived,
        recordsPrepared:
            Int =
            _state.value
                .recordsPrepared,
        lastCompletedAtMs:
            Long? =
            _state.value
                .lastCompletedAtMs
    ) {
        val previous =
            _state.value

        val updatedLog =
            if (
                previous.log
                    .lastOrNull() ==
                message
            ) {
                previous.log
            } else {
                (
                    previous.log +
                        message
                )
                    .takeLast(
                        16
                    )
            }

        _state.value =
            HistoryBackfillState(
                stage =
                    stage,
                message =
                    message,
                requestedFromMinute =
                    requestedFromMinute,
                recordsReceived =
                    recordsReceived,
                recordsPrepared =
                    recordsPrepared,
                lastCompletedAtMs =
                    lastCompletedAtMs,
                error =
                    if (
                        stage ==
                        HistoryBackfillStage
                            .FAILED
                    ) {
                        message
                    } else {
                        null
                    },
                log =
                    updatedLog
            )
    }

    private fun fail(
        message: String
    ) {
        handler
            .removeCallbacksAndMessages(
                null
            )

        update(
            HistoryBackfillStage
                .FAILED,
            message
        )

        completion =
            null

        closing =
            true

        closeGatt()

        releaseGlobalSlot()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val current =
            gatt

        gatt =
            null

        if (
            current != null
        ) {
            runCatching {
                current.disconnect()
            }

            runCatching {
                current.close()
            }
        }
    }

    private fun hasConnectPermission():
        Boolean =
        Build.VERSION.SDK_INT <
            31 ||
            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                ) ==
            PackageManager
                .PERMISSION_GRANTED

    private fun acquireGlobalSlot():
        Boolean =
        synchronized(
            GLOBAL_LOCK
        ) {
            if (
                globalClientActive
            ) {
                false
            } else {
                globalClientActive =
                    true
                true
            }
        }

    private fun releaseGlobalSlot() {
        synchronized(
            GLOBAL_LOCK
        ) {
            globalClientActive =
                false
        }
    }

    companion object {
        private const val MIN_GLUCOSE_MGDL =
            18

        private const val MAX_GLUCOSE_MGDL =
            800

        private const val HISTORY_TIMEOUT_MS =
            60_000L

        private const val SESSION_KEY_READ_RETRY_MS =
            125L

        private const val SESSION_KEY_READ_MAX_RETRIES =
            12

        private val ACTIVE_STAGES =
            setOf(
                HistoryBackfillStage
                    .CONNECTING,
                HistoryBackfillStage
                    .DISCOVERING,
                HistoryBackfillStage
                    .CONFIGURING,
                HistoryBackfillStage
                    .REQUESTING,
                HistoryBackfillStage
                    .RECEIVING
            )

        private val GLOBAL_LOCK =
            Any()

        @Volatile
        private var globalClientActive =
            false
    }
}
