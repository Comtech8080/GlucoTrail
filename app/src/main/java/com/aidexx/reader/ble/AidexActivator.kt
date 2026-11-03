package com.aidexx.reader.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aidexx.reader.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Experimental AiDEX X new-sensor activation client.
 *
 * The command order is based on the known-good Android HCI activation capture:
 * bond -> F001 KEY0 -> F002 KEY1 -> 0x10 -> 0x21 -> reconnect ->
 * 0x10 -> 0x31 -> 0x20(time) -> 0x11 -> 0x21 -> 0x35 -> 0x34.
 *
 * No OTA characteristic is discovered or written by this class.
 */
class AidexActivator(private val context: Context) {
    private val _state = MutableStateFlow(ActivationState())
    val state: StateFlow<ActivationState> = _state

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var serial: String = ""
    private var iv: ByteArray = byteArrayOf()
    private var key0: ByteArray? = null
    private var key1: ByteArray? = null

    private var f001: BluetoothGattCharacteristic? = null
    private var f002: BluetoothGattCharacteristic? = null
    private var f003: BluetoothGattCharacteristic? = null
    private var f005: BluetoothGattCharacteristic? = null

    private var reconnectingForTimeInit = false
    private var secondPass = false
    private var plannedDisconnect = false
    private var waitingForF001Bond = false

    private enum class NotifyStep { NONE, F003, F002, F001 }
    private var notifyStep = NotifyStep.NONE

    private var f001WriteCompleted = false
    private var f001MasterKeyReceived = false
    private var sessionReadStarted = false
    private var sessionReadRetryCount = 0

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (changed?.address != device?.address) return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDING -> update(ActivationStage.BONDING, "LE Secure Connections bonding in progress")
                BluetoothDevice.BOND_BONDED -> {
                    log("Bluetooth bond established")
                    if (waitingForF001Bond) {
                        waitingForF001Bond = false
                        val g = gatt ?: return
                        val c = f001 ?: return fail("F001 missing after bonding")
                        enableNotify(g, c)
                    } else {
                        configurePrivateChannels()
                    }
                }
                BluetoothDevice.BOND_NONE -> if (_state.value.stage == ActivationStage.BONDING) fail("Bluetooth bonding failed")
            }
        }
    }

    init {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice, serial: String) {
        if (!hasConnectPermission()) return fail("Bluetooth connect permission is not granted")
        closeGatt()
        this.device = device
        this.serial = serial.trim().uppercase()
        this.iv = AidexCrypto.serialIv(this.serial)
        this.key0 = null
        this.key1 = null
        this.secondPass = false
        this.reconnectingForTimeInit = false
        this.plannedDisconnect = false
        this.waitingForF001Bond = false
        if (!AidexProtocol.isValidSerial(this.serial)) return fail("AiDEX X serial must be exactly 10 letters/numbers")
        update(ActivationStage.CONNECTING, "Connecting to AiDEX X-${this.serial}", serial = this.serial, address = device.address)
        connect()
    }

    fun cancel() {
        plannedDisconnect = true
        closeGatt()
        _state.value = ActivationState(stage = ActivationStage.IDLE, message = "Cancelled")
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val d = device ?: return fail("Bluetooth device unavailable")
        plannedDisconnect = false
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                update(ActivationStage.DISCOVERING_SERVICES, if (secondPass) "Reconnected. Discovering CGM service" else "Discovering CGM service")
                g.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (reconnectingForTimeInit) {
                    reconnectingForTimeInit = false
                    secondPass = true
                    update(ActivationStage.CONNECTING, "Reconnecting to initialize sensor clock")
                    executor.schedule({ connect() }, 500, TimeUnit.MILLISECONDS)
                } else if (!plannedDisconnect && _state.value.stage !in setOf(ActivationStage.STARTED, ActivationStage.FAILED, ActivationStage.IDLE)) {
                    fail("Sensor disconnected, GATT status $status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Service discovery failed: $status")
            val service = g.getService(AidexProtocol.SERVICE_UUID) ?: return fail("CGM service 0x181F not found")
            f001 = service.getCharacteristic(AidexProtocol.F001_UUID)
            f002 = service.getCharacteristic(AidexProtocol.F002_UUID)
            f003 = service.getCharacteristic(AidexProtocol.F003_UUID)
            f005 = service.getCharacteristic(AidexProtocol.F005_UUID)
            if (f001 == null || f002 == null || f003 == null) return fail("Required AiDEX private characteristics not found")

            // Match the captured official flow: F003/F002 are configured first.
            // F001 requires authentication on an unused sensor and is what drives bonding.
            configurePrivateChannels()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val characteristicUuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The captured unused sensor rejected the first F001 CCCD write until LE Secure
                // Connections bonding completed. Android ATT status 5/15 represent authentication/
                // encryption requirements. Retry F001 only after the bond is established.
                if (characteristicUuid == AidexProtocol.F001_UUID && status in setOf(5, 15)) {
                    val d = device ?: return fail("Bluetooth device unavailable during bonding")
                    waitingForF001Bond = true
                    update(ActivationStage.BONDING, "Sensor requested secure Bluetooth bonding")
                    if (d.bondState == BluetoothDevice.BOND_BONDED) {
                        waitingForF001Bond = false
                        enableNotify(g, f001 ?: return fail("F001 missing"))
                    } else if (d.bondState == BluetoothDevice.BOND_NONE && !d.createBond()) {
                        fail("Android could not start LE Secure Connections bonding")
                    }
                    return
                }
                return fail("Notification setup failed for $characteristicUuid: $status")
            }
            when (characteristicUuid) {
                AidexProtocol.F003_UUID -> {
                    log("F003 notifications enabled")
                    notifyStep = NotifyStep.F002
                    enableNotify(g, f002 ?: return fail("F002 missing"))
                }
                AidexProtocol.F002_UUID -> {
                    log("F002 notifications enabled")
                    readF005ForMode(g)
                }
                AidexProtocol.F001_UUID -> {
                    log("F001 notifications enabled")
                    f001WriteCompleted = false
                    f001MasterKeyReceived = false
                    sessionReadStarted = false
                    sessionReadRetryCount = 0

                    update(ActivationStage.REQUESTING_MASTER_KEY, "Requesting sensor master key")
                    val request = AidexCrypto.keyRequest(serial)
                    write(g, f001 ?: return fail("F001 missing"), request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    log("F001 key request sent")
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (
                characteristic.uuid ==
                AidexProtocol.F001_UUID
            ) {
                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    return fail(
                        "F001 key-request write failed: $status"
                    )
                }

                f001WriteCompleted = true
                log("F001 key-request write completed")
                maybeReadSessionKey(g)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Characteristic read failed ${characteristic.uuid}: $status")
            when (characteristic.uuid) {
                AidexProtocol.F005_UUID -> {
                    log("F005 communication mode: ${value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
                    if (key0 == null) {
                        notifyStep = NotifyStep.F001
                        enableNotify(g, f001 ?: return fail("F001 missing"))
                    } else {
                        readSessionKey(g)
                    }
                }
                AidexProtocol.F002_UUID -> {
                    val master = key0 ?: return fail("F002 returned before KEY0")
                    val session = AidexCrypto.decodeSessionKey(master, iv, value) ?: return fail("Session key CRC8 validation failed")
                    key1 = session
                    sessionReadStarted = false
                    log("Session key established and CRC8 verified")
                    sendEncrypted(AidexProtocol.START_INFO_COMMAND, ActivationStage.REQUESTING_DEVICE_INFO, "Requesting sensor information")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                AidexProtocol.F001_UUID -> {
                    if (value.size != 16) return fail("Unexpected F001 key length ${value.size}")
                    key0 = value.copyOf()
                    f001MasterKeyReceived = true
                    log(
                        if (f001WriteCompleted) {
                            "Master key received; starting F002 session-key read"
                        } else {
                            "Master key received; waiting for F001 write completion"
                        }
                    )
                    maybeReadSessionKey(g)
                }
                AidexProtocol.F002_UUID -> processF002(value)
                AidexProtocol.F003_UUID -> {
                    log("F003 current-data notification received")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(g, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, characteristic, characteristic.value ?: byteArrayOf())
        }
    }

    @SuppressLint("MissingPermission")
    private fun configurePrivateChannels() {
        val g = gatt ?: return
        notifyStep = NotifyStep.F003
        update(if (key0 == null) ActivationStage.REQUESTING_MASTER_KEY else ActivationStage.READING_SESSION_KEY, "Configuring AiDEX private channels")
        enableNotify(g, f003 ?: return fail("F003 missing"))
    }

    @SuppressLint("MissingPermission")
    private fun readF005ForMode(g: BluetoothGatt) {
        val c = f005
        if (c != null) {
            if (!g.readCharacteristic(c)) {
                if (key0 == null) {
                    notifyStep = NotifyStep.F001
                    enableNotify(g, f001 ?: return fail("F001 missing"))
                } else {
                    readSessionKey(g)
                }
            }
        } else if (key0 == null) {
            notifyStep = NotifyStep.F001
            enableNotify(g, f001 ?: return fail("F001 missing"))
        } else {
            readSessionKey(g)
        }
    }

    private fun maybeReadSessionKey(
        g: BluetoothGatt
    ) {
        if (
            sessionReadStarted ||
            !f001WriteCompleted ||
            !f001MasterKeyReceived ||
            key0 == null
        ) {
            return
        }

        readSessionKey(g)
    }

    @SuppressLint("MissingPermission")
    private fun readSessionKey(
        g: BluetoothGatt
    ) {
        if (sessionReadStarted) return
        if (key0 == null) return fail("Master key unavailable")

        val c = f002 ?: return fail("F002 missing")

        update(
            ActivationStage.READING_SESSION_KEY,
            "Reading encrypted F002 session key"
        )

        if (
            g.readCharacteristic(c)
        ) {
            sessionReadStarted = true
            sessionReadRetryCount = 0
            log("F002 session-key read started")
            return
        }

        sessionReadRetryCount += 1

        if (
            sessionReadRetryCount >
            12
        ) {
            return fail(
                "Android GATT remained busy and could not start F002 read"
            )
        }

        log(
            "Android GATT busy; retrying F002 read ${sessionReadRetryCount}/12"
        )

        executor.schedule(
            {
                if (
                    _state.value.stage !=
                    ActivationStage.FAILED &&
                    !sessionReadStarted
                ) {
                    readSessionKey(g)
                }
            },
            125,
            TimeUnit.MILLISECONDS
        )
    }

    private fun processF002(encrypted: ByteArray) {
        val session = key1 ?: return fail("Encrypted response received before KEY1")
        val clear = AidexProtocol.decryptAndValidate(session, iv, encrypted) ?: return fail("F002 CRC16 validation failed")
        val type = AidexProtocol.responseType(clear) ?: return
        log("F002 response 0x${type.toString(16).uppercase().padStart(3, '0')} CRC OK")

        when (type) {
            0x110 -> {
                if (secondPass) {
                    sendEncrypted(AidexProtocol.CONFIG_31_COMMAND, ActivationStage.SETTING_TIME, "Requesting startup configuration")
                } else {
                    sendEncrypted(AidexProtocol.ASK_START_TIME_COMMAND, ActivationStage.SETTING_TIME, "Checking factory start time")
                }
            }

            0x121 -> {
                val year = if (clear.size >= 4) (clear[2].toInt() and 0xFF) or ((clear[3].toInt() and 0xFF) shl 8) else 0
                if (!secondPass && year == 0) {
                    log("Sensor reports no start time. First activation pass complete")
                    reconnectingForTimeInit = true
                    plannedDisconnect = true
                    key1 = null // A fresh F002 session key is required after every reconnect.
                    try { gatt?.disconnect() }
                    catch (_: SecurityException) { fail("Bluetooth permission was revoked") }
                    catch (_: Exception) { fail("Could not reconnect for time initialization") }
                } else {
                    sendEncrypted(AidexProtocol.FINALIZE_35_COMMAND, ActivationStage.FINALIZING, "Start time confirmed. Finalizing startup")
                }
            }

            0x131 -> {
                val timeCommand = AidexProtocol.makeTimeCommand()
                sendEncrypted(timeCommand, ActivationStage.SETTING_TIME, "Writing local time and timezone")
            }

            0x120, 0x020 -> {
                if (secondPass) {
                    sendEncrypted(AidexProtocol.FINALIZE_11_COMMAND, ActivationStage.FINALIZING, "Clock accepted. Requesting current sensor state")
                } else {
                    sendEncrypted(AidexProtocol.ASK_START_TIME_COMMAND, ActivationStage.SETTING_TIME, "Checking sensor start time")
                }
            }

            0x111 -> sendEncrypted(AidexProtocol.ASK_START_TIME_COMMAND, ActivationStage.SETTING_TIME, "Reading initialized start time")
            0x135 -> sendEncrypted(AidexProtocol.FINALIZE_34_COMMAND, ActivationStage.FINALIZING, "Completing startup")
            0x134 -> {
                update(ActivationStage.STARTED, "Sensor started. Warm-up is approximately 60 minutes.")
                log("Activation sequence completed")
                // Release GATT so normal passive glucose advertisements can resume.
                plannedDisconnect = true
                executor.schedule({
                    try { gatt?.disconnect() }
                    catch (_: SecurityException) { log("Bluetooth permission was revoked before disconnect") }
                    catch (_: Exception) {}
                }, 250, TimeUnit.MILLISECONDS)
            }

            else -> {
                log("Unknown/unused startup response 0x${type.toString(16).uppercase()}; no command sent")
            }
        }
    }

    private fun sendEncrypted(clearWithCrc: ByteArray, stage: ActivationStage, message: String) {
        val session = key1 ?: return fail("Session key unavailable")
        val g = gatt ?: return fail("GATT unavailable")
        val c = f002 ?: return fail("F002 unavailable")
        val encrypted = AidexProtocol.encryptedCommand(session, iv, clearWithCrc)
        update(stage, message)
        executor.schedule({ write(g, c, encrypted, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }, 25, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) return fail("Could not enable notifications for ${c.uuid}")
        val cccd = c.getDescriptor(AidexProtocol.CCCD_UUID) ?: return fail("CCCD missing for ${c.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            val status = g.writeDescriptor(cccd, value)
            if (status != BluetoothStatusCodes.SUCCESS) fail("CCCD write rejected: $status")
        } else {
            @Suppress("DEPRECATION") cccd.value = value
            @Suppress("DEPRECATION") if (!g.writeDescriptor(cccd)) fail("CCCD write could not start")
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, writeType: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            val status = g.writeCharacteristic(c, value, writeType)
            if (status != BluetoothStatusCodes.SUCCESS) fail("GATT write rejected: $status")
        } else {
            @Suppress("DEPRECATION") c.writeType = writeType
            @Suppress("DEPRECATION") c.value = value
            @Suppress("DEPRECATION") if (!g.writeCharacteristic(c)) fail("GATT write could not start")
        }
    }

    private fun update(stage: ActivationStage, message: String, serial: String = this.serial, address: String? = device?.address) {
        val line = "${System.currentTimeMillis()}: $message"
        _state.value = _state.value.copy(
            stage = stage,
            message = message,
            serial = serial,
            address = address,
            log = (_state.value.log + line).takeLast(100),
            error = if (stage == ActivationStage.FAILED) message else null
        )
    }

    private fun log(message: String) {
        val current = _state.value
        val line = "${System.currentTimeMillis()}: $message"
        _state.value = current.copy(log = (current.log + line).takeLast(100))
    }

    private fun fail(message: String) = update(ActivationStage.FAILED, message)

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
