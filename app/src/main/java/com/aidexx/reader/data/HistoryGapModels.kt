package com.aidexx.reader.data

data class HistoryGap(
    val startMinute: Int,
    val endMinute: Int
) {
    val minuteCount: Int
        get() =
            (
                endMinute -
                    startMinute +
                    1
            )
                .coerceAtLeast(
                    0
                )
}

data class HistoryGapSummary(
    val serial: String,
    val currentSensorMinute: Int,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val missingMinutes: Int,
    val gaps: List<HistoryGap>,
    val checkedAtMs: Long =
        System.currentTimeMillis()
) {
    val needsBackfill: Boolean
        get() =
            gaps.isNotEmpty()

    val largestGapMinutes: Int
        get() =
            gaps.maxOfOrNull {
                it.minuteCount
            } ?: 0

    val signature: String
        get() =
            gaps.joinToString(
                separator = ";"
            ) {
                "${it.startMinute}-${it.endMinute}"
            }
}
