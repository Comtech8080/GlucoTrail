package com.aidexx.reader.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class GlucoseSummarySample(
    val receivedAtMs: Long,
    val glucoseMgDl: Int
)

object GlucosePeriodSummaryCalculator {
    private const val BUCKET_MINUTES =
        180

    private const val BUCKET_COUNT =
        8

    /*
     * If accepted readings disappear for this long, treat the next low/high
     * value as the start of a new observable event rather than assuming the
     * same excursion continued through unknown data.
     */
    private const val EVENT_CONTINUITY_MS =
        15L * 60L * 1000L

    private const val VERY_HIGH_MMOL =
        13.4

    fun calculate(
        serial: String,
        days: Int,
        samples: List<GlucoseSummarySample>,
        lowMmol: Double,
        highMmol: Double,
        windowStartMs: Long,
        windowEndMs: Long,
        zone: ZoneId =
            ZoneId.systemDefault()
    ): GlucosePeriodSummaryReport {
        val ordered =
            samples
                .asSequence()
                .filter {
                    it.receivedAtMs in
                        windowStartMs..windowEndMs &&
                        it.glucoseMgDl >
                        0
                }
                .sortedBy {
                    it.receivedAtMs
                }
                .toList()

        val bucketSums =
            DoubleArray(
                BUCKET_COUNT
            )

        val bucketCounts =
            IntArray(
                BUCKET_COUNT
            )

        val lowEvents =
            IntArray(
                BUCKET_COUNT
            )

        val highEvents =
            IntArray(
                BUCKET_COUNT
            )

        val veryHighEvents =
            IntArray(
                BUCKET_COUNT
            )

        val daysWithData =
            linkedSetOf<LocalDate>()

        var overallSum =
            0.0

        var wasLow =
            false

        var wasHigh =
            false

        var wasVeryHigh =
            false

        var previousAtMs:
            Long? =
            null

        ordered.forEach {
                sample ->
            val local =
                Instant
                    .ofEpochMilli(
                        sample.receivedAtMs
                    )
                    .atZone(
                        zone
                    )

            daysWithData +=
                local.toLocalDate()

            val minuteOfDay =
                local.hour *
                    60 +
                    local.minute

            val bucket =
                (
                    minuteOfDay /
                        BUCKET_MINUTES
                )
                    .coerceIn(
                        0,
                        BUCKET_COUNT -
                            1
                    )

            val mgDl =
                sample.glucoseMgDl
                    .toDouble()

            val mmol =
                mgDl /
                    18.0

            bucketSums[
                bucket
            ] +=
                mgDl

            bucketCounts[
                bucket
            ] +=
                1

            overallSum +=
                mgDl

            val previous =
                previousAtMs

            if (
                previous == null ||
                sample.receivedAtMs -
                    previous >
                EVENT_CONTINUITY_MS
            ) {
                wasLow =
                    false

                wasHigh =
                    false

                wasVeryHigh =
                    false
            }

            val isLow =
                mmol <=
                    lowMmol

            val displayedMmol =
                kotlin.math.round(
                    mmol *
                        10.0
                ) /
                    10.0

            val isHigh =
                mmol >
                    highMmol

            val isVeryHigh =
                displayedMmol >=
                    VERY_HIGH_MMOL

            if (
                isLow &&
                !wasLow
            ) {
                lowEvents[
                    bucket
                ] +=
                    1
            }

            if (
                isHigh &&
                !wasHigh
            ) {
                highEvents[
                    bucket
                ] +=
                    1
            }

            if (
                isVeryHigh &&
                !wasVeryHigh
            ) {
                veryHighEvents[
                    bucket
                ] +=
                    1
            }

            wasLow =
                isLow

            wasHigh =
                isHigh

            wasVeryHigh =
                isVeryHigh

            previousAtMs =
                sample.receivedAtMs
        }

        val totalReadings =
            ordered.size

        val buckets =
            List(
                BUCKET_COUNT
            ) {
                    index ->
                GlucosePeriodBucket(
                    startMinuteOfDay =
                        index *
                            BUCKET_MINUTES,
                    endMinuteOfDay =
                        (
                            index +
                                1
                        ) *
                            BUCKET_MINUTES,
                    readingCount =
                        bucketCounts[
                            index
                        ],
                    averageMgDl =
                        if (
                            bucketCounts[
                                index
                            ] >
                            0
                        ) {
                            bucketSums[
                                index
                            ] /
                                bucketCounts[
                                    index
                                ]
                        } else {
                            null
                        },
                    lowEvents =
                        lowEvents[
                            index
                        ],
                    highEvents =
                        highEvents[
                            index
                        ],
                    veryHighEvents =
                        veryHighEvents[
                            index
                        ]
                )
            }

        return GlucosePeriodSummaryReport(
            serial =
                serial.uppercase(),
            days =
                days,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                windowEndMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                totalReadings,
            totalLowEvents =
                lowEvents.sum(),
            totalHighEvents =
                highEvents.sum(),
            totalVeryHighEvents =
                veryHighEvents.sum(),
            overallAverageMgDl =
                if (
                    totalReadings >
                    0
                ) {
                    overallSum /
                        totalReadings
                } else {
                    null
                },
            buckets =
                buckets
        )
    }
}
