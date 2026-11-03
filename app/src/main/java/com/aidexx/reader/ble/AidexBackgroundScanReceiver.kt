package com.aidexx.reader.ble

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aidexx.reader.alarm.ContinuousMonitoringStatus
import com.aidexx.reader.alarm.GlucoseAlarmEvaluator
import com.aidexx.reader.data.AidexHistoryDatabase
import com.aidexx.reader.data.CalibrationTimeline
import com.aidexx.reader.protocol.AidexAdvertisementParser
import com.aidexx.reader.protocol.AidexReading
import com.aidexx.reader.protocol.AidexSensor
import java.util.concurrent.Executors

class AidexBackgroundScanReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action !=
            ACTION_SCAN_RESULT
        ) {
            return
        }

        val pendingResult =
            goAsync()

        EXECUTOR.execute {
            try {
                processIntent(
                    context.applicationContext,
                    intent
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processIntent(
        context: Context,
        intent: Intent
    ) {
        val errorCode =
            intent.getIntExtra(
                BluetoothLeScanner
                    .EXTRA_ERROR_CODE,
                0
            )

        if (
            errorCode !=
            0
        ) {
            ContinuousMonitoringStatus
                .markBackgroundScan(
                    context =
                        context,
                    active =
                        false,
                    error =
                        "Android background BLE scan error $errorCode."
                )

            return
        }

        val results =
            scanResults(
                intent
            )

        if (
            results.isEmpty()
        ) {
            return
        }

        val prefs =
            context.getSharedPreferences(
                APP_PREFS,
                Context.MODE_PRIVATE
            )

        val selectedSerial =
            prefs.getString(
                "selected_sensor_serial",
                null
            )
                ?.trim()
                ?.uppercase()
                ?: return

        val expectedAddress =
            prefs.getString(
                "background_scan_address",
                null
            )
                ?.trim()
                ?.uppercase()
                ?: return

        val database =
            AidexHistoryDatabase(
                context
            )

        try {
            val savedSensor =
                database
                    .loadSavedSensors()
                    .firstOrNull {
                        it.serial.equals(
                            selectedSerial,
                            ignoreCase =
                                true
                        )
                    }

            results.forEach {
                    result ->
                val address =
                    runCatching {
                        result.device.address
                    }
                        .getOrNull()
                        ?.uppercase()
                        ?: return@forEach

                if (
                    address !=
                    expectedAddress
                ) {
                    return@forEach
                }

                val reading =
                    newestReading(
                        serial =
                            selectedSerial,
                        result =
                            result
                    )
                        ?: return@forEach

                val sensor =
                    AidexSensor(
                        name =
                            savedSensor
                                ?.name
                                ?: "AiDEX X-$selectedSerial",
                        serial =
                            selectedSerial,
                        address =
                            address,
                        rssi =
                            result.rssi,
                        reading =
                            reading
                    )

                database.saveSensor(
                    sensor
                )

                ContinuousMonitoringStatus
                    .markBackgroundPacket(
                        context =
                            context,
                        serial =
                            selectedSerial,
                        address =
                            address,
                        sensorMinute =
                            reading.sensorMinute,
                        crcValid =
                            reading.crcValid
                    )

                val calibratedMgDl =
                    CalibrationTimeline
                        .applyMgDl(
                            rawMgDl =
                                reading
                                    .glucoseMgDl
                                    .toDouble(),
                            receivedAtMs =
                                reading
                                    .receivedAtMs,
                            events =
                                database
                                    .loadCalibrations(
                                        selectedSerial
                                    )
                        )

                /*
                 * High/Low notifications can therefore be evaluated even if
                 * the foreground service process was suspended or killed.
                 */
                GlucoseAlarmEvaluator
                    .evaluateReading(
                        context =
                            context,
                        reading =
                            reading,
                        calibratedMgDl =
                            calibratedMgDl
                    )
            }
        } finally {
            database.close()
        }
    }

    private fun newestReading(
        serial: String,
        result: ScanResult
    ): AidexReading? {
        val record =
            result.scanRecord
                ?: return null

        val raw =
            RawAdvertisementParser
                .parse(
                    record.bytes
                )

        val candidates =
            buildList {
                raw.manufacturerData
                    .filter {
                        it.companyId ==
                            AidexAdvertisementParser
                                .COMPANY_ID
                    }
                    .forEach {
                        add(
                            it.payload
                        )
                    }

                record
                    .getManufacturerSpecificData(
                        AidexAdvertisementParser
                            .COMPANY_ID
                    )
                    ?.let {
                            androidPayload ->
                        if (
                            none {
                                it.contentEquals(
                                    androidPayload
                                )
                            }
                        ) {
                            add(
                                androidPayload
                            )
                        }
                    }
            }

        return candidates
            .asSequence()
            .mapNotNull {
                    payload ->
                AidexAdvertisementParser
                    .parse(
                        serial =
                            serial,
                        payload =
                            payload,
                        rssi =
                            result.rssi
                    )
            }
            .maxByOrNull {
                it.sensorMinute
            }
    }

    @Suppress("DEPRECATION")
    private fun scanResults(
        intent: Intent
    ): List<ScanResult> =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            intent
                .getParcelableArrayListExtra(
                    BluetoothLeScanner
                        .EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                )
                .orEmpty()
        } else {
            intent
                .getParcelableArrayListExtra<ScanResult>(
                    BluetoothLeScanner
                        .EXTRA_LIST_SCAN_RESULT
                )
                .orEmpty()
        }

    companion object {
        const val ACTION_SCAN_RESULT =
            "com.aidexx.reader.action.BACKGROUND_BLE_SCAN"

        private const val APP_PREFS =
            "aidexx_reader"

        private val EXECUTOR =
            Executors
                .newSingleThreadExecutor()
    }
}
