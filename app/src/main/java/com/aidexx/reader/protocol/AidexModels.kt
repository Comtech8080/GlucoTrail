package com.aidexx.reader.protocol

data class AidexReading(
    val serial: String,
    val sensorMinute: Int,
    val glucoseMgDl: Int,
    val trendRaw: Int,
    val quality: Int,
    val valid: Boolean,
    val previousMgDl: Int?,
    val previous2MgDl: Int?,
    val crcValid: Boolean,
    val rssi: Int,
    val receivedAtMs: Long = System.currentTimeMillis()
) {
    val glucoseMmol: Double get() = glucoseMgDl / 18.0
    val rateMgDlPerMinute: Double get() = trendRaw / 10.0
    val isWarmup: Boolean get() = sensorMinute < 60

    val trendArrow: String
        get() = when {
            rateMgDlPerMinute >= 3.0 -> "⇈"
            rateMgDlPerMinute >= 2.0 -> "↑"
            rateMgDlPerMinute >= 1.0 -> "↗"
            rateMgDlPerMinute <= -3.0 -> "⇊"
            rateMgDlPerMinute <= -2.0 -> "↓"
            rateMgDlPerMinute <= -1.0 -> "↘"
            else -> "→"
        }
}

data class AidexSensor(
    val name: String,
    val serial: String,
    val address: String,
    val rssi: Int,
    val reading: AidexReading?
)

enum class ActivationStage {
    IDLE,
    SEARCHING,
    CONNECTING,
    DISCOVERING_SERVICES,
    REQUESTING_MASTER_KEY,
    READING_SESSION_KEY,
    REQUESTING_DEVICE_INFO,
    BONDING,
    SETTING_TIME,
    FINALIZING,
    STARTED,
    FAILED
}

data class ActivationState(
    val stage: ActivationStage = ActivationStage.IDLE,
    val message: String = "Ready",
    val serial: String = "",
    val address: String? = null,
    val log: List<String> = emptyList(),
    val error: String? = null
)


data class BleScanStatus(
    val running: Boolean = false,
    val totalResults: Long = 0,
    val service181fResults: Long = 0,
    val manufacturer0059Results: Long = 0,
    val aidexNameResults: Long = 0,
    val aidexCandidates: Long = 0,
    val lastDeviceName: String? = null,
    val lastDeviceAddress: String? = null,
    val lastManufacturer0059Length: Int? = null,
    val lastManufacturer0059Hex: String? = null,
    val lastAdvertisementSensorMinute: Int? = null,
    val lastAdvertisementSeedHex: String? = null,
    val lastAdvertisementCalculatedCrcHex: String? = null,
    val lastAdvertisementReceivedCrcHex: String? = null,
    val lastAdvertisementCrcValid: Boolean? = null,
    val lastError: String? = null
)
