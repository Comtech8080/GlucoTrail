package com.aidexx.reader.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.aidexx.reader.protocol.AidexAdvertisementParser
import com.aidexx.reader.protocol.AidexProtocol
import com.aidexx.reader.protocol.AidexSensor
import com.aidexx.reader.protocol.BleScanStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AidexBleScanner(private val context: Context) {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    }

    private val _sensors = MutableStateFlow<List<AidexSensor>>(emptyList())
    val sensors: StateFlow<List<AidexSensor>> = _sensors

    private val _status = MutableStateFlow(BleScanStatus())
    val status: StateFlow<BleScanStatus> = _status

    private var scanning = false

    /*
     * AiDEX X uses legacy advertising plus scan-response data. Android/Samsung
     * can deliver those pieces in different callbacks. Keep a small cache by
     * BLE address so we can join:
     *
     *   ADV:  service 0x181F + manufacturer 0x0059 glucose packet
     *   SCAN_RSP: local name AiDEX X-<serial> + manufacturer 0x0059
     */
    private data class PendingPayload(
        val bytes: ByteArray,
        val receivedAtMs: Long,
        val rssi: Int
    )

    private data class PendingDevice(
        var name: String? = null,
        var service181f: Boolean = false,
        var rssi: Int = Int.MIN_VALUE,
        val manufacturerPayloads:
            MutableList<PendingPayload> =
            mutableListOf()
    )

    private val pending = linkedMapOf<String, PendingDevice>()
    private val candidateAddresses = linkedSetOf<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = process(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::process)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _status.value = _status.value.copy(
                running = false,
                lastError = "Android BLE scan failed: $errorCode"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start(
        deviceAddress: String? =
            null
    ) {
        if (
            scanning ||
            !hasRequiredPermissions()
        ) {
            return
        }

        val adapter =
            bluetoothAdapter

        if (
            adapter ==
            null
        ) {
            _status.value =
                _status.value.copy(
                    lastError =
                        "Bluetooth adapter unavailable"
                )
            return
        }

        if (
            !adapter.isEnabled
        ) {
            _status.value =
                _status.value.copy(
                    lastError =
                        "Bluetooth is turned off"
                )
            return
        }

        val scanner =
            adapter.bluetoothLeScanner

        if (
            scanner ==
            null
        ) {
            _status.value =
                _status.value.copy(
                    lastError =
                        "BLE scanner unavailable"
                )
            return
        }

        try {
            pending.clear()
            candidateAddresses.clear()
            _sensors.value =
                emptyList()
            _status.value =
                BleScanStatus(
                    running =
                        true
                )

            val normalizedAddress =
                deviceAddress
                    ?.trim()
                    ?.uppercase()
                    ?.takeIf {
                        BluetoothAdapter
                            .checkBluetoothAddress(
                                it
                            )
                    }

            if (
                normalizedAddress !=
                null
            ) {
                val filter =
                    ScanFilter
                        .Builder()
                        .setDeviceAddress(
                            normalizedAddress
                        )
                        .build()

                val settings =
                    ScanSettings
                        .Builder()
                        .setScanMode(
                            ScanSettings
                                .SCAN_MODE_LOW_LATENCY
                        )
                        .setCallbackType(
                            ScanSettings
                                .CALLBACK_TYPE_ALL_MATCHES
                        )
                        .build()

                scanner.startScan(
                    listOf(
                        filter
                    ),
                    settings,
                    callback
                )
            } else {
                scanner.startScan(
                    callback
                )
            }

            scanning =
                true
        } catch (e: SecurityException) {
            scanning =
                false
            _status.value =
                BleScanStatus(
                    running =
                        false,
                    lastError =
                        "BLE scan permission rejected by Android: ${e.message}"
                )
        } catch (e: Exception) {
            scanning =
                false
            _status.value =
                BleScanStatus(
                    running =
                        false,
                    lastError =
                        "BLE scan could not start: ${e.javaClass.simpleName}: ${e.message}"
                )
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        }
        scanning = false
        _status.value = _status.value.copy(running = false)
    }

    @SuppressLint("MissingPermission")
    private fun process(result: ScanResult) {
        val record = result.scanRecord ?: return
        val raw = RawAdvertisementParser.parse(record.bytes)

        val address = try {
            result.device.address ?: return
        } catch (_: SecurityException) {
            return
        }

        val fallbackName = try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }

        val callbackName = raw.localName ?: record.deviceName ?: fallbackName

        val service181f =
            0x181F in raw.serviceUuids16 ||
                record.serviceUuids?.contains(ParcelUuid(AidexProtocol.SERVICE_UUID)) == true

        val raw0059 = raw.manufacturerData
            .filter { it.companyId == AidexAdvertisementParser.COMPANY_ID }
            .map { it.payload }

        val android0059 = record.getManufacturerSpecificData(
            AidexAdvertisementParser.COMPANY_ID
        )

        val callbackPayloads = buildList {
            addAll(raw0059)
            if (android0059 != null && none { it.contentEquals(android0059) }) {
                add(android0059)
            }
        }

        val has0059 = callbackPayloads.isNotEmpty()
        val aidexName = AidexProtocol.serialFromName(callbackName) != null

        val old = _status.value
        _status.value = old.copy(
            totalResults = old.totalResults + 1,
            service181fResults = old.service181fResults + if (service181f) 1 else 0,
            manufacturer0059Results = old.manufacturer0059Results + if (has0059) 1 else 0,
            aidexNameResults = old.aidexNameResults + if (aidexName) 1 else 0,
            lastDeviceName = callbackName ?: "(unnamed)",
            lastDeviceAddress = address
        )

        val state = pending.getOrPut(address) { PendingDevice() }
        if (!callbackName.isNullOrBlank()) state.name = callbackName
        if (service181f) state.service181f = true
        state.rssi = result.rssi

        val receivedAtMs =
            System.currentTimeMillis()

        callbackPayloads.forEach {
                payload ->
            if (
                state.manufacturerPayloads
                    .none {
                        it.bytes
                            .contentEquals(
                                payload
                            )
                    }
            ) {
                /*
                 * Preserve the time at which Android actually delivered this
                 * unique manufacturer packet. Re-parsing a cached packet later
                 * must not make an old sensor minute look like "Just now".
                 */
                if (
                    state.manufacturerPayloads
                        .size >=
                    12
                ) {
                    state.manufacturerPayloads
                        .removeAt(
                            0
                        )
                }

                state.manufacturerPayloads +=
                    PendingPayload(
                        bytes =
                            payload.copyOf(),
                        receivedAtMs =
                            receivedAtMs,
                        rssi =
                            result.rssi
                    )
            }
        }

        val serialFromName = AidexProtocol.serialFromName(state.name)

        /*
         * A valid AiDEX glucose packet is a much stronger discriminator than
         * Nordic company ID 0x0059 by itself. This lets us identify the sensor
         * even if Samsung withholds or delays the scan-response name.
         */
        val validAnonymousReading =
            state.manufacturerPayloads
                .asSequence()
                .mapNotNull {
                        cached ->
                    AidexAdvertisementParser
                        .parse(
                            serial =
                                serialFromName
                                    ?: "UNKNOWN",
                            payload =
                                cached.bytes,
                            rssi =
                                cached.rssi
                        )
                        ?.copy(
                            receivedAtMs =
                                cached
                                    .receivedAtMs
                        )
                }
                .filter {
                    it.crcValid
                }
                .maxByOrNull {
                    it.sensorMinute
                }

        val looksLikeAidex =
            serialFromName != null ||
                (state.service181f && validAnonymousReading != null)

        if (!looksLikeAidex) return

        candidateAddresses += address
        _status.value = _status.value.copy(
            aidexCandidates = candidateAddresses.size.toLong(),
            lastDeviceName = state.name ?: "AiDEX X candidate",
            lastDeviceAddress = address
        )

        val serial = serialFromName ?: "UNKNOWN-${
            address.replace(":", "").takeLast(6)
        }"

        val parsed =
            state.manufacturerPayloads
                .mapNotNull {
                        cached ->
                    AidexAdvertisementParser
                        .parse(
                            serial =
                                serial,
                            payload =
                                cached.bytes,
                            rssi =
                                cached.rssi
                        )
                        ?.copy(
                            receivedAtMs =
                                cached
                                    .receivedAtMs
                        )
                }

        /*
         * CRC-failed advertisements are diagnostic transport evidence only.
         * They must never become the sensor's current glucose/warm-up record,
         * because their minute/glucose/status fields are not trustworthy.
         * The raw packet and CRC debug values remain available through
         * BleScanStatus for Diagnostics.
         */
        val reading =
            parsed
                .filter {
                    it.crcValid
                }
                .maxByOrNull {
                    it.sensorMinute
                }

        val newestCachedPayload =
            state.manufacturerPayloads
                .filter {
                    it.bytes.size >=
                        2
                }
                .maxByOrNull {
                    (
                        it.bytes[0]
                            .toInt() and
                            0xFF
                    ) or
                        (
                            (
                                it.bytes[1]
                                    .toInt() and
                                    0xFF
                            ) shl 8
                        )
                }

        newestCachedPayload
            ?.let {
                    cached ->
                val debug =
                    AidexAdvertisementParser
                        .debug(
                            cached.bytes
                        )

                fun hex32(
                    value: Long?
                ): String? =
                    value?.let {
                        "0x" +
                            it
                                .toString(
                                    16
                                )
                                .uppercase()
                                .padStart(
                                    8,
                                    '0'
                                )
                    }

                _status.value =
                    _status.value.copy(
                        lastManufacturer0059Length =
                            debug.payloadLength,
                        lastManufacturer0059Hex =
                            debug.payloadHex,
                        lastAdvertisementSensorMinute =
                            debug.sensorMinute,
                        lastAdvertisementSeedHex =
                            hex32(
                                debug.seed
                            ),
                        lastAdvertisementCalculatedCrcHex =
                            hex32(
                                debug.calculatedCrc
                            ),
                        lastAdvertisementReceivedCrcHex =
                            hex32(
                                debug.receivedCrc
                            ),
                        lastAdvertisementCrcValid =
                            debug.crcValid
                    )
            }

        val displayName = state.name ?: "AiDEX X candidate ($address)"
        val sensor = AidexSensor(
            name = displayName,
            serial = serial,
            address = address,
            rssi = state.rssi,
            reading = reading
        )

        val updated = _sensors.value.toMutableList()
        val index = updated.indexOfFirst { it.address == address }
        if (index >= 0) {
            updated[index] = sensor
        } else {
            updated += sensor
        }
        _sensors.value = updated.sortedByDescending { it.rssi }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            val scan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            scan && fineLocation
        } else {
            fineLocation
        }
    }
}
