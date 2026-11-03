package com.aidexx.reader.data

import com.aidexx.reader.protocol.AidexReading
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTimelineTest {
    private fun event(
        effectiveAtMs: Long,
        offsetMgDl: Int
    ) =
        CalibrationEvent(
            serial =
                "TEST",
            effectiveAtMs =
                effectiveAtMs,
            createdAtMs =
                effectiveAtMs,
            sensorMinute =
                100,
            rawCgmMgDl =
                180,
            bloodMgDl =
                180 +
                    offsetMgDl,
            offsetMgDl =
                offsetMgDl
        )

    @Test
    fun readingsBeforeCalibrationRemainRaw() {
        val events =
            listOf(
                event(
                    10_000L,
                    18
                )
            )

        assertEquals(
            180.0,
            CalibrationTimeline
                .applyMgDl(
                    rawMgDl =
                        180.0,
                    receivedAtMs =
                        9_999L,
                    events =
                        events
                ),
            0.001
        )
    }

    @Test
    fun offsetAppliesAtAndAfterCalibration() {
        val events =
            listOf(
                event(
                    10_000L,
                    -18
                )
            )

        assertEquals(
            162.0,
            CalibrationTimeline
                .applyMgDl(
                    rawMgDl =
                        180.0,
                    receivedAtMs =
                        10_000L,
                    events =
                        events
                ),
            0.001
        )
    }

    @Test
    fun newestCalibrationWins() {
        val events =
            listOf(
                event(
                    10_000L,
                    18
                ),
                event(
                    20_000L,
                    -9
                )
            )

        assertEquals(
            171.0,
            CalibrationTimeline
                .applyMgDl(
                    rawMgDl =
                        180.0,
                    receivedAtMs =
                        25_000L,
                    events =
                        events
                ),
            0.001
        )
    }

    @Test
    fun resetEventReturnsFutureReadingsToRaw() {
        val events =
            listOf(
                event(
                    10_000L,
                    18
                ),
                event(
                    20_000L,
                    0
                )
            )

        assertEquals(
            180.0,
            CalibrationTimeline
                .applyMgDl(
                    rawMgDl =
                        180.0,
                    receivedAtMs =
                        25_000L,
                    events =
                        events
                ),
            0.001
        )
    }

    @Test
    fun readingCopyIsAdjustedWithoutChangingRawObject() {
        val raw =
            AidexReading(
                serial =
                    "TEST",
                sensorMinute =
                    100,
                glucoseMgDl =
                    180,
                trendRaw =
                    0,
                quality =
                    100,
                valid =
                    true,
                previousMgDl =
                    179,
                previous2MgDl =
                    178,
                crcValid =
                    true,
                rssi =
                    -70,
                receivedAtMs =
                    20_000L
            )

        val adjusted =
            CalibrationTimeline
                .applyReading(
                    raw,
                    listOf(
                        event(
                            10_000L,
                            18
                        )
                    )
                )

        assertEquals(
            180,
            raw.glucoseMgDl
        )

        assertEquals(
            198,
            adjusted.glucoseMgDl
        )
    }
}
