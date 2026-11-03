package com.aidexx.reader.data

data class GlucosePeriodBucket(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val readingCount: Int,
    val averageMgDl: Double?,
    val lowEvents: Int,
    val highEvents: Int,
    val veryHighEvents: Int
)

data class GlucosePeriodSummaryReport(
    val serial: String,
    val days: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val availableDays: Int,
    val totalReadings: Int,
    val totalLowEvents: Int,
    val totalHighEvents: Int,
    val totalVeryHighEvents: Int,
    val overallAverageMgDl: Double?,
    val buckets: List<GlucosePeriodBucket>
)
