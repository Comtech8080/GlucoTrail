package com.aidexx.reader.data

data class SensorUsageReport(
    val serial: String,
    val days: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val availableDays: Int,
    val totalReadings: Int,
    val readingsPerDay: Double,
    val expectedSensorMinutes: Int,
    val capturedPercent: Double,
    val firstSensorMinute: Int?,
    val lastSensorMinute: Int?
)
