package com.aidexx.reader.data

object SensorUsageCalculator {
    fun expectedMinutes(
        firstSensorMinute: Int?,
        lastSensorMinute: Int?
    ): Int {
        if (
            firstSensorMinute ==
            null ||
            lastSensorMinute ==
            null ||
            lastSensorMinute <
            firstSensorMinute
        ) {
            return 0
        }

        return lastSensorMinute -
            firstSensorMinute +
            1
    }

    fun capturedPercent(
        acceptedReadings: Int,
        expectedMinutes: Int
    ): Double =
        if (
            expectedMinutes <=
            0
        ) {
            0.0
        } else {
            (
                acceptedReadings
                    .toDouble() /
                    expectedMinutes
                        .toDouble() *
                    100.0
            )
                .coerceIn(
                    0.0,
                    100.0
                )
        }
}
