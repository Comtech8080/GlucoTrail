package com.aidexx.reader

import com.aidexx.reader.data.BloodPressureEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressureReportMathTest {
    @Test fun categoryBoundariesAndMixedReadings() {
        val cases = listOf(
            Triple(89, 65, BpCategory.LOW), Triple(110, 59, BpCategory.LOW),
            Triple(90, 60, BpCategory.NORMAL), Triple(119, 79, BpCategory.NORMAL),
            Triple(120, 79, BpCategory.ELEVATED), Triple(129, 79, BpCategory.ELEVATED),
            Triple(130, 79, BpCategory.STAGE_ONE), Triple(119, 80, BpCategory.STAGE_ONE),
            Triple(139, 89, BpCategory.STAGE_ONE), Triple(140, 60, BpCategory.STAGE_TWO),
            Triple(100, 90, BpCategory.STAGE_TWO), Triple(160, 55, BpCategory.STAGE_TWO),
            Triple(85, 95, BpCategory.STAGE_TWO)
        )
        cases.forEach { (sys, dia, expected) -> assertEquals("$sys/$dia", expected, bpCategory(sys, dia)) }
    }
    @Test fun percentagesAreAccurateAndTotalOneHundred() {
        assertEquals(listOf(0, 0, 0, 0, 0), bpPercentages(listOf(0, 0, 0, 0, 0)))
        assertEquals(listOf(0, 55, 22, 19, 4), bpPercentages(listOf(0, 55, 22, 19, 4)))
        assertEquals(listOf(34, 33, 33, 0, 0), bpPercentages(listOf(1, 1, 1, 0, 0)))
        for (count in 1..100) {
            val result = bpPercentages(listOf(count, 3, 8, 12, 1))
            assertEquals(100, result.sum())
            assertTrue(result.all { it in 0..100 })
        }
    }
    @Test fun windowsExcludeOldAndFutureEntriesAndSortByTime() {
        val now = 200L * 86_400_000L
        val start = now - 7L * 86_400_000L
        fun entry(id: Long, time: Long) = BloodPressureEntry(id, time, 120, 80)
        val records = listOf(entry(1, now), entry(2, start - 1), entry(3, start), entry(4, now + 1))
        assertEquals(listOf(3L, 1L), bpWindow(records, 7, now).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), bpWindow(records, 180, now).map { it.id })
        assertTrue(bpWindow(emptyList(), 7, now).isEmpty())
    }
}
