package com.aidexx.reader.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class A1cCalculatorTest {
    @Test
    fun gmiFromKnownMeanGlucose() {
        val result = A1cCalculator.fromAverageMgDl(155.0)
        assertEquals(7.0176, result.gmiPercent, 0.0001)
        assertEquals(8.6111, result.averageMmol, 0.001)
    }

    @Test
    fun mmolAndMgDlPathsAgree() {
        val fromMmol = A1cCalculator.fromAverageMmol(10.0)
        val fromMg = A1cCalculator.fromAverageMgDl(180.0)
        assertEquals(fromMg.gmiPercent, fromMmol.gmiPercent, 0.0001)
    }
}
