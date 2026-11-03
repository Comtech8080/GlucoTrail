package com.aidexx.reader.data

import com.aidexx.reader.protocol.AidexReading
import kotlin.math.roundToInt

data class CalibrationEvent(
    val serial: String,
    val effectiveAtMs: Long,
    val createdAtMs: Long,
    val sensorMinute: Int,
    val rawCgmMgDl: Int,
    val bloodMgDl: Int,
    val offsetMgDl: Int
) {
    val offsetMmol: Double
        get() =
            offsetMgDl /
                18.0
}

object CalibrationTimeline {
    fun eventFor(
        receivedAtMs: Long,
        events: List<CalibrationEvent>
    ): CalibrationEvent? =
        events
            .asSequence()
            .filter {
                it.effectiveAtMs <=
                    receivedAtMs
            }
            .maxByOrNull {
                it.effectiveAtMs
            }

    fun applyMgDl(
        rawMgDl: Double,
        receivedAtMs: Long,
        events: List<CalibrationEvent>
    ): Double {
        val event =
            eventFor(
                receivedAtMs,
                events
            )

        return (
            rawMgDl +
                (
                    event
                        ?.offsetMgDl
                        ?: 0
                )
        )
            .coerceIn(
                MIN_DISPLAY_MGDL,
                MAX_DISPLAY_MGDL
            )
    }

    fun applyReading(
        reading: AidexReading,
        events: List<CalibrationEvent>
    ): AidexReading {
        if (
            events.isEmpty()
        ) {
            return reading
        }

        val current =
            applyMgDl(
                rawMgDl =
                    reading.glucoseMgDl
                        .toDouble(),
                receivedAtMs =
                    reading.receivedAtMs,
                events =
                    events
            )
                .roundToInt()

        val previous =
            reading.previousMgDl
                ?.let {
                    applyMgDl(
                        rawMgDl =
                            it.toDouble(),
                        receivedAtMs =
                            reading.receivedAtMs -
                                60_000L,
                        events =
                            events
                    )
                        .roundToInt()
                }

        val previous2 =
            reading.previous2MgDl
                ?.let {
                    applyMgDl(
                        rawMgDl =
                            it.toDouble(),
                        receivedAtMs =
                            reading.receivedAtMs -
                                120_000L,
                        events =
                            events
                    )
                        .roundToInt()
                }

        return reading.copy(
            glucoseMgDl =
                current,
            previousMgDl =
                previous,
            previous2MgDl =
                previous2
        )
    }

    private const val MIN_DISPLAY_MGDL =
        18.0

    private const val MAX_DISPLAY_MGDL =
        800.0
}
