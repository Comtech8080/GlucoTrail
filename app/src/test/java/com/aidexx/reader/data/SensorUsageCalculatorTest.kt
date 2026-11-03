package com.aidexx.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorUsageCalculatorTest {
    @Test
    fun inclusiveMinuteSpanIsCalculated() {
        assertEquals(
            101,
            SensorUsageCalculator
                .expectedMinutes(
                    firstSensorMinute =
                        100,
                    lastSensorMinute =
                        200
                )
        )
    }

    @Test
    fun capturedPercentageUsesAcceptedReadings() {
        assertEquals(
            80.0,
            SensorUsageCalculator
                .capturedPercent(
                    acceptedReadings =
                        80,
                    expectedMinutes =
                        100
                ),
            0.001
        )
    }

    @Test
    fun capturePercentageCannotExceedHundred() {
        assertEquals(
            100.0,
            SensorUsageCalculator
                .capturedPercent(
                    acceptedReadings =
                        105,
                    expectedMinutes =
                        100
                ),
            0.001
        )
    }
}
