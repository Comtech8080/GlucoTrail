package com.aidexx.reader.data

import com.aidexx.reader.protocol.AidexReading
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares two independent AiDEX X glucose streams without changing either stream.
 * An older sensor is NOT a blood glucose reference and must never automatically
 * calibrate a replacement sensor. Any actual calibration requires a fingerstick
 * reference and the existing per-sensor calibration workflow.
 */
object SensorOverlapComparison {
    const val DEFAULT_MAX_SAMPLE_AGE_MS = 5 * 60_000L
    const val DEFAULT_MAX_PAIR_DISTANCE_MS = 2 * 60_000L
    const val DEFAULT_DIFFERENCE_MGDL = 27 // 1.5 mmol/L

    data class PairedReading(
        val primary: AidexReading,
        val replacement: AidexReading,
        val differenceMgDl: Int,
        val differencePercent: Double,
        val significantDisagreement: Boolean
    ) {
        val differenceMmol: Double get() = differenceMgDl / 18.0
    }

    data class ReferenceComparison(
        val fingerstickMgDl: Int,
        val primaryErrorMgDl: Int,
        val replacementErrorMgDl: Int
    )

    fun eligible(reading: AidexReading, nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_SAMPLE_AGE_MS): Boolean =
        reading.valid && reading.crcValid && !reading.isWarmup &&
            reading.glucoseMgDl in 18..800 &&
            reading.receivedAtMs <= nowMs &&
            nowMs - reading.receivedAtMs <= maxAgeMs

    /**
     * Returns null if either stream is untrustworthy, stale, or not measured
     * close enough in time for a meaningful comparison.
     */
    fun pair(
        primary: List<AidexReading>,
        replacement: List<AidexReading>,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_SAMPLE_AGE_MS,
        maxPairDistanceMs: Long = DEFAULT_MAX_PAIR_DISTANCE_MS,
        differenceThresholdMgDl: Int = DEFAULT_DIFFERENCE_MGDL
    ): PairedReading? {
        require(maxAgeMs >= 0 && maxPairDistanceMs >= 0 && differenceThresholdMgDl > 0)
        val a = primary.asSequence().filter { eligible(it, nowMs, maxAgeMs) }
            .maxByOrNull { it.receivedAtMs } ?: return null
        val b = replacement.asSequence().filter {
            eligible(it, nowMs, maxAgeMs) &&
                abs(it.receivedAtMs - a.receivedAtMs) <= maxPairDistanceMs &&
                !it.serial.equals(a.serial, ignoreCase = true)
        }.minByOrNull { abs(it.receivedAtMs - a.receivedAtMs) } ?: return null
        val diff = abs(a.glucoseMgDl - b.glucoseMgDl)
        val percent = diff * 100.0 / max(1.0, (a.glucoseMgDl + b.glucoseMgDl) / 2.0)
        return PairedReading(a, b, diff, percent, diff >= differenceThresholdMgDl)
    }

    /** Comparison only; this function never writes a sensor calibration. */
    fun compareFingerstick(pair: PairedReading, fingerstickMgDl: Int): ReferenceComparison {
        require(fingerstickMgDl in 18..800)
        return ReferenceComparison(
            fingerstickMgDl,
            abs(pair.primary.glucoseMgDl - fingerstickMgDl),
            abs(pair.replacement.glucoseMgDl - fingerstickMgDl)
        )
    }

    /**
     * A replacement must have a current, verified post-warmup packet to be
     * considered for promotion. A missing primary packet alone does not
     * establish sensor end of life.
     */
    fun replacementReady(
        replacement: List<AidexReading>,
        nowMs: Long
    ): Boolean = replacement.any { eligible(it, nowMs) }
}
