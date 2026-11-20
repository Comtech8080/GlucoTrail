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

@SuppressLint("MissingPermission")
class GlucoRingBleAdvertiser(context: Context) {

    companion object {
        val SERVICE_UUID: UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    }

    private val advertiser: BluetoothLeAdvertiser? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.bluetoothLeAdvertiser

    fun publishReading(glucoseMgDl: Int, trendRaw: Int, valid: Boolean = true) {
        val adv = advertiser ?: run {
            Log.w("GlucoRing", "BLE advertiser not available on this device")
            return
        }

        val payload = byteArrayOf(
            (if (valid) 0x01 else 0x00).toByte(),
            (glucoseMgDl and 0xFF).toByte(),
            ((glucoseMgDl shr 8) and 0xFF).toByte(),
            trendRaw.toByte()
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
                Log.i("GlucoRing", "Reading advertised: $glucoseMgDl mg/dL, trend $trendRaw")
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