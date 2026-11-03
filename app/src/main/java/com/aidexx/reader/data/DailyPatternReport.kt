package com.aidexx.reader.data

data class DailyPatternPoint(
    val minuteOfDay: Int,
    val p10MgDl: Double,
    val p25MgDl: Double,
    val medianMgDl: Double,
    val p75MgDl: Double,
    val p90MgDl: Double,
    val sampleCount: Int
)

data class DailyPatternReport(
    val serial: String,
    val days: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val availableDays: Int,
    val totalReadings: Int,
    val bucketMinutes: Int,
    val points: List<DailyPatternPoint>
)
