package com.aidexx.reader.data

object HistoryGapCalculator {
    fun calculate(
        serial: String,
        currentSensorMinute: Int,
        presentMinutes: List<Int>,
        windowMinutes: Int =
            72 * 60,
        graceMinutes: Int =
            2,
        minimumGapMinutes: Int =
            3,
        firstValidSensorMinute: Int =
            60
    ): HistoryGapSummary {
        val current =
            currentSensorMinute
                .coerceAtLeast(
                    0
                )

        val start =
            maxOf(
                firstValidSensorMinute,
                current -
                    windowMinutes
                        .coerceAtLeast(
                            1
                        ) +
                    1
            )

        val end =
            current -
                graceMinutes
                    .coerceAtLeast(
                        0
                    )

        if (
            end <
            start
        ) {
            return HistoryGapSummary(
                serial =
                    serial.uppercase(),
                currentSensorMinute =
                    current,
                windowStartMinute =
                    start,
                windowEndMinute =
                    end,
                missingMinutes =
                    0,
                gaps =
                    emptyList()
            )
        }

        val present =
            presentMinutes
                .asSequence()
                .filter {
                    it in
                    start..end
                }
                .distinct()
                .sorted()
                .toList()

        val gaps =
            ArrayList<HistoryGap>()

        var expected =
            start

        present.forEach {
                minute ->
            if (
                minute >
                expected
            ) {
                val gap =
                    HistoryGap(
                        startMinute =
                            expected,
                        endMinute =
                            minute -
                                1
                    )

                if (
                    gap.minuteCount >=
                    minimumGapMinutes
                ) {
                    gaps +=
                        gap
                }
            }

            if (
                minute >=
                expected
            ) {
                expected =
                    minute +
                        1
            }
        }

        if (
            expected <=
            end
        ) {
            val trailing =
                HistoryGap(
                    startMinute =
                        expected,
                    endMinute =
                        end
                )

            if (
                trailing.minuteCount >=
                minimumGapMinutes
            ) {
                gaps +=
                    trailing
            }
        }

        return HistoryGapSummary(
            serial =
                serial.uppercase(),
            currentSensorMinute =
                current,
            windowStartMinute =
                start,
            windowEndMinute =
                end,
            missingMinutes =
                gaps.sumOf {
                    it.minuteCount
                },
            gaps =
                gaps
        )
    }
}
