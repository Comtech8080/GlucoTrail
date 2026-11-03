package com.aidexx.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGapCalculatorTest {
    @Test
    fun completeHistoryHasNoGaps() {
        val present =
            (60..410).toList()

        val result =
            HistoryGapCalculator
                .calculate(
                    serial =
                        "TEST",
                    currentSensorMinute =
                        412,
                    presentMinutes =
                        present
                )

        assertFalse(
            result.needsBackfill
        )

        assertEquals(
            0,
            result.missingMinutes
        )
    }

    @Test
    fun threeMinuteGapTriggersRecovery() {
        val present =
            (60..410)
                .filterNot {
                    it in
                    200..202
                }

        val result =
            HistoryGapCalculator
                .calculate(
                    serial =
                        "TEST",
                    currentSensorMinute =
                        412,
                    presentMinutes =
                        present
                )

        assertTrue(
            result.needsBackfill
        )

        assertEquals(
            3,
            result.missingMinutes
        )

        assertEquals(
            200,
            result.gaps[0]
                .startMinute
        )

        assertEquals(
            202,
            result.gaps[0]
                .endMinute
        )
    }

    @Test
    fun oneAndTwoMinuteGapsAreIgnored() {
        val present =
            (60..410)
                .filterNot {
                    it ==
                    200 ||
                    it in
                    300..301
                }

        val result =
            HistoryGapCalculator
                .calculate(
                    serial =
                        "TEST",
                    currentSensorMinute =
                        412,
                    presentMinutes =
                        present
                )

        assertFalse(
            result.needsBackfill
        )
    }

    @Test
    fun newestTwoMinutesAreGracePeriod() {
        val present =
            (60..408).toList()

        val result =
            HistoryGapCalculator
                .calculate(
                    serial =
                        "TEST",
                    currentSensorMinute =
                        412,
                    presentMinutes =
                        present
                )

        /*
         * Minute 409-410 are inside the check, 411-412 are not.
         * Only a two-minute gap remains, so no encrypted recovery yet.
         */
        assertFalse(
            result.needsBackfill
        )
    }

    @Test
    fun warmupIsNeverReportedMissing() {
        val present =
            (60..100).toList()

        val result =
            HistoryGapCalculator
                .calculate(
                    serial =
                        "TEST",
                    currentSensorMinute =
                        102,
                    presentMinutes =
                        present
                )

        assertFalse(
            result.needsBackfill
        )

        assertEquals(
            60,
            result.windowStartMinute
        )
    }
}
