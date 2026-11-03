package com.aidexx.reader.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aidexx.reader.alarm.ContinuousMonitoringStatus

object AidexBackgroundScanManager {
    private const val REQUEST_CODE =
        51750

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        serial: String,
        deviceAddress: String?
    ): Boolean {
        val appContext =
            context.applicationContext

        val address =
            deviceAddress
                ?.trim()
                ?.uppercase()
                ?.takeIf {
                    BluetoothAdapter
                        .checkBluetoothAddress(
                            it
                        )
                }
                ?: run {
                    ContinuousMonitoringStatus
                        .markBackgroundScan(
                            context =
                                appContext,
                            active =
                                false,
                            error =
                                "Selected sensor BLE address is unavailable."
                        )

                    return false
                }

        if (
            !hasScanPermission(
                appContext
            )
        ) {
            ContinuousMonitoringStatus
                .markBackgroundScan(
                    context =
                        appContext,
                    active =
                        false,
                    error =
                        "Bluetooth scan permission is missing."
                )

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
            ContinuousMonitoringStatus
                .markBackgroundScan(
                    context =
                        appContext,
                    active =
                        false,
                    error =
                        "Bluetooth is unavailable or turned off."
                )

            return false
        }

        val scanner =
            adapter.bluetoothLeScanner
                ?: run {
                    ContinuousMonitoringStatus
                        .markBackgroundScan(
                            context =
                                appContext,
                            active =
                                false,
                            error =
                                "Android BLE scanner is unavailable."
                        )

                    return false
                }

        val filter =
            ScanFilter
                .Builder()
                .setDeviceAddress(
                    address
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
                .setReportDelay(
                    0L
                )
                .build()

        val pendingIntent =
            pendingIntent(
                appContext
            )

        val result =
            runCatching {
                scanner.startScan(
                    listOf(
                        filter
                    ),
                    settings,
                    pendingIntent
                )
            }
                .getOrElse {
                    ContinuousMonitoringStatus
                        .markBackgroundScan(
                            context =
                                appContext,
                            active =
                                false,
                            error =
                                "${
                                    it.javaClass
                                        .simpleName
                                }: ${
                                    it.message
                                        ?: "PendingIntent BLE scan failed"
                                }"
                        )

                    return false
                }

        val success =
            result ==
                0

        ContinuousMonitoringStatus
            .markBackgroundScan(
                context =
                    appContext,
                active =
                    success,
                error =
                    if (
                        success
                    ) {
                        null
                    } else {
                        "Android PendingIntent BLE scan returned error $result."
                    }
            )

        if (
            success
        ) {
            appContext
                .getSharedPreferences(
                    "aidexx_reader",
                    Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                    "background_scan_serial",
                    serial.uppercase()
                )
                .putString(
                    "background_scan_address",
                    address
                )
                .apply()
        }

        return success
    }

    @SuppressLint("MissingPermission")
    fun stop(
        context: Context
    ) {
        val appContext =
            context.applicationContext

        if (
            hasScanPermission(
                appContext
            )
        ) {
            runCatching {
                appContext
                    .getSystemService(
                        BluetoothManager::class.java
                    )
                    ?.adapter
                    ?.bluetoothLeScanner
                    ?.stopScan(
                        pendingIntent(
                            appContext
                        )
                    )
            }
        }

        appContext
            .getSharedPreferences(
                "aidexx_reader",
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(
                "background_scan_serial"
            )
            .remove(
                "background_scan_address"
            )
            .apply()

        ContinuousMonitoringStatus
            .markBackgroundScan(
                context =
                    appContext,
                active =
                    false,
                error =
                    null
            )
    }

    private fun pendingIntent(
        context: Context
    ): PendingIntent {
        val intent =
            Intent(
                context,
                AidexBackgroundScanReceiver::class.java
            ).apply {
                action =
                    AidexBackgroundScanReceiver
                        .ACTION_SCAN_RESULT
            }

        val mutability =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                PendingIntent
                    .FLAG_MUTABLE
            } else {
                0
            }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent
                .FLAG_UPDATE_CURRENT or
                mutability
        )
    }

    private fun hasScanPermission(
        context: Context
    ): Boolean =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_SCAN
                ) ==
                PackageManager
                    .PERMISSION_GRANTED
        } else {
            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                ) ==
                PackageManager
                    .PERMISSION_GRANTED
        }
}
