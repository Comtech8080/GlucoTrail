package com.aidexx.reader.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GlucosePeriodSummaryCalculatorTest {
    private val utc =
        ZoneId.of(
            "UTC"
        )

    @Test
    fun continuousLowReadingsCountAsOneEvent() {
        val base =
            1_700_000_000_000L

        val samples =
            listOf(
                GlucoseSummarySample(
                    base,
                    100
                ),
                GlucoseSummarySample(
                    base + 60_000L,
                    70
                ),
                GlucoseSummarySample(
                    base + 120_000L,
                    68
                ),
                GlucoseSummarySample(
                    base + 180_000L,
                    69
                ),
                GlucoseSummarySample(
                    base + 240_000L,
                    100
                )
            )

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        samples,
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            300_000L,
                    zone =
                        utc
                )

        assertEquals(
            1,
            report.totalLowEvents
        )
    }

    @Test
    fun lowReentryCountsSecondEvent() {
        val base =
            1_700_000_000_000L

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        listOf(
                            GlucoseSummarySample(
                                base,
                                70
                            ),
                            GlucoseSummarySample(
                                base + 60_000L,
                                100
                            ),
                            GlucoseSummarySample(
                                base + 120_000L,
                                69
                            )
                        ),
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            180_000L,
                    zone =
                        utc
                )

        assertEquals(
            2,
            report.totalLowEvents
        )
    }

    @Test
    fun continuousHighReadingsCountAsOneEvent() {
        val base =
            1_700_000_000_000L

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        listOf(
                            GlucoseSummarySample(
                                base,
                                120
                            ),
                            GlucoseSummarySample(
                                base + 60_000L,
                                190
                            ),
                            GlucoseSummarySample(
                                base + 120_000L,
                                220
                            ),
                            GlucoseSummarySample(
                                base + 180_000L,
                                170
                            )
                        ),
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            240_000L,
                    zone =
                        utc
                )

        assertEquals(
            1,
            report.totalHighEvents
        )
    }

    @Test
    fun continuousVeryHighReadingsCountAsOneEvent() {
        val base =
            1_700_000_000_000L

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        listOf(
                            GlucoseSummarySample(
                                base,
                                180
                            ),
                            GlucoseSummarySample(
                                base + 60_000L,
                                245
                            ),
                            GlucoseSummarySample(
                                base + 120_000L,
                                260
                            ),
                            GlucoseSummarySample(
                                base + 180_000L,
                                250
                            ),
                            GlucoseSummarySample(
                                base + 240_000L,
                                180
                            )
                        ),
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            300_000L,
                    zone =
                        utc
                )

        assertEquals(
            1,
            report.totalVeryHighEvents
        )
    }

    @Test
    fun veryHighReentryCountsSecondEvent() {
        val base =
            1_700_000_000_000L

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        listOf(
                            GlucoseSummarySample(
                                base,
                                245
                            ),
                            GlucoseSummarySample(
                                base + 60_000L,
                                210
                            ),
                            GlucoseSummarySample(
                                base + 120_000L,
                                250
                            )
                        ),
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            180_000L,
                    zone =
                        utc
                )

        assertEquals(
            2,
            report.totalVeryHighEvents
        )
    }

    @Test
    fun averageUsesAllAcceptedSamples() {
        val base =
            1_700_000_000_000L

        val report =
            GlucosePeriodSummaryCalculator
                .calculate(
                    serial =
                        "TEST",
                    days =
                        7,
                    samples =
                        listOf(
                            GlucoseSummarySample(
                                base,
                                100
                            ),
                            GlucoseSummarySample(
                                base + 60_000L,
                                200
                            )
                        ),
                    lowMmol =
                        4.5,
                    highMmol =
                        10.0,
                    windowStartMs =
                        base -
                            1L,
                    windowEndMs =
                        base +
                            120_000L,
                    zone =
                        utc
                )

        assertNotNull(
            report.overallAverageMgDl
        )

        assertEquals(
            150.0,
            report.overallAverageMgDl!!,
            0.001
        )
    }
}
