package com.aidexx.reader.data

data class TimeInRangeReport(
    val serial: String,
    val days: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val availableDays: Int,
    val totalReadings: Int,
    val lowReadings: Int,
    val targetReadings: Int,
    val highReadings: Int,
    val veryHighReadings: Int
) {
    val lowPercent: Double
        get() =
            percent(
                lowReadings
            )

    val targetPercent: Double
        get() =
            percent(
                targetReadings
            )

    val highPercent: Double
        get() =
            percent(
                highReadings
            )

    val veryHighPercent: Double
        get() =
            percent(
                veryHighReadings
            )

    private fun percent(
        count: Int
    ): Double =
        if (
            totalReadings <= 0
        ) {
            0.0
        } else {
            (
                count.toDouble() /
                    totalReadings
                        .toDouble()
            ) *
                100.0
        }
}
