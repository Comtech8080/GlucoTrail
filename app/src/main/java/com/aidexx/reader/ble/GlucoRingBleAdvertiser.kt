package com.aidexx.reader.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import android.annotation.SuppressLint

/**
 * Broadcasts the latest glucose reading so a nearby GlucoRing can pick it up
 * by passively scanning BLE advertisements.
 *
 * Wire protocol (8 bytes of service data under [SERVICE_UUID]):
 *
 *   byte 0        version = 0x01
 *   byte 1        flags   (bit 0 = valid, bit 1 = mmol/L if set, else mg/dL)
 *   byte 2..3     glucose mg/dL, uint16 little-endian
 *   byte 4        trend raw, int8 (units of 0.1 mg/dL per minute)
 *   byte 5        quality (0..100)
 *   byte 6..7     sensor minute, uint16 little-endian
 */
@SuppressLint("MissingPermission")
class GlucoRingBleAdvertiser(context: Context) {

    companion object {
        val SERVICE_UUID: UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

        private const val PAYLOAD_VERSION: Int = 0x01
    }

    private val advertiser: BluetoothLeAdvertiser? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.bluetoothLeAdvertiser

    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) stop()
    }

    fun publishReading(
        glucoseMgDl: Int,
        trendRaw: Int,
        quality: Int,
        sensorMinute: Int,
        valid: Boolean = true,
        mmol: Boolean = false
    ) {
        if (!enabled) return

        val adv = advertiser ?: run {
            Log.w("GlucoRing", "BLE advertiser not available on this device")
            return
        }

        val flags = (if (valid) 0x01 else 0x00) or (if (mmol) 0x02 else 0x00)
        val payload = byteArrayOf(
            PAYLOAD_VERSION.toByte(),
            flags.toByte(),
            (glucoseMgDl and 0xFF).toByte(),
            ((glucoseMgDl shr 8) and 0xFF).toByte(),
            trendRaw.toByte(),
            (quality and 0xFF).toByte(),
            (sensorMinute and 0xFF).toByte(),
            ((sensorMinute shr 8) and 0xFF).toByte()
        )

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE_UUID), payload)
            .build()

        try {
            adv.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i(
                        "GlucoRing",
                        "Reading advertised: $glucoseMgDl mg/dL, trend $trendRaw, quality $quality"
                    )
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e("GlucoRing", "Advertise failed: $errorCode")
                }
            })
        } catch (e: SecurityException) {
            Log.w("GlucoRing", "BLE advertise permission missing: ${e.message}")
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        } catch (e: SecurityException) {
            Log.w("GlucoRing", "BLE advertise permission missing: ${e.message}")
        }
    }
}