package com.aidexx.reader.data

import com.aidexx.reader.protocol.AidexReading
import org.junit.Assert.*
import org.junit.Test

class SensorOverlapComparisonTest {
    private fun reading(
        serial: String,
        mgDl: Int,
        timestamp: Long = 1_000_000L,
        minute: Int = 100,
        crc: Boolean = true,
        valid: Boolean = true
    ) = AidexReading(
        serial = serial,
        sensorMinute = minute,
        glucoseMgDl = mgDl,
        trendRaw = 0,
        quality = 0,
        valid = valid,
        previousMgDl = null,
        previous2MgDl = null,
        crcValid = crc,
        rssi = -60,
        receivedAtMs = timestamp
    )

    @Test fun twoHealthyStreamsCanBeCompared() {
        val paired = SensorOverlapComparison.pair(
            listOf(reading("OLD", 112)),
            listOf(reading("NEW", 140)),
            1_010_000L
        )
        assertNotNull(paired)
        assertEquals(28, paired!!.differenceMgDl)
        assertTrue(paired.significantDisagreement)
        assertEquals(28.0 / 18.0, paired.differenceMmol, 0.0001)
        val fingerstick = SensorOverlapComparison.compareFingerstick(paired, 117)
        assertEquals(5, fingerstick.primaryErrorMgDl)
        assertEquals(23, fingerstick.replacementErrorMgDl)
    }

    @Test fun refusesWarmupAndInvalidAndStale() {
        val old = listOf(reading("OLD", 120))
        assertNull(SensorOverlapComparison.pair(old, listOf(reading("NEW", 150, minute = 40)), 1_010_000L))
        assertNull(SensorOverlapComparison.pair(old, listOf(reading("NEW", 150, crc = false)), 1_010_000L))
        assertNull(SensorOverlapComparison.pair(old, listOf(reading("NEW", 150, valid = false)), 1_010_000L))
        assertNull(SensorOverlapComparison.pair(old, listOf(reading("NEW", 150)), 1_400_000L))
    }

    @Test fun rejectsDistantTimeSamplesAndSameSerial() {
        val old = listOf(reading("OLD", 120))
        assertNull(SensorOverlapComparison.pair(
            old, listOf(reading("NEW", 151, timestamp = 1_180_000L)), 1_190_000L
        ))
        assertNull(SensorOverlapComparison.pair(old, listOf(reading("OLD", 151)), 1_010_000L))
    }

    @Test fun replacementMustBeReadyWithoutGuessingExpiration() {
        assertFalse(SensorOverlapComparison.replacementReady(listOf(reading("NEW", 130, minute = 10)), 1_010_000L))
        assertTrue(SensorOverlapComparison.replacementReady(listOf(reading("NEW", 130)), 1_010_000L))
        assertFalse(SensorOverlapComparison.replacementReady(listOf(reading("NEW", 130)), 1_400_000L))
    }
}
