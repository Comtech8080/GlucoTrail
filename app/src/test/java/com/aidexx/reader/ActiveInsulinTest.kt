package com.aidexx.reader

import com.aidexx.reader.data.GlucoseNote
import org.junit.Assert.*
import org.junit.Test

class ActiveInsulinTest {
    private fun dose(id: Long = 1, at: Long = 0, minutes: Int? = 240, units: Double = 2.0) =
        GlucoseNote(id = id, serial = "TEST", timestampMs = at, fastInsulinUnits = units, fastInsulinActionMinutes = minutes)
    @Test fun overlapsAndSnapshotDurations() {
        val doses = listOf(dose(), dose(2, 60_000L, 120))
        val active = activeInsulinDoses(doses, 120_000L)
        assertEquals(listOf(2L, 1L), active.map { it.note.id })
        assertEquals(119 * 60_000L, active[0].remainingMs(120_000L))
        assertEquals(240 * 60_000L, active[1].finishMs)
        assertEquals(listOf(1L), activeInsulinDoses(doses, 121 * 60_000L).map { it.note.id })
    }
    @Test fun boundariesAndLegacy() {
        assertEquals(1, activeInsulinDoses(listOf(dose()), 0L).size)
        assertTrue(activeInsulinDoses(listOf(dose()), 240 * 60_000L).isEmpty())
        assertTrue(activeInsulinDoses(listOf(dose(at=100), dose(minutes=null), dose(minutes=1), dose(units=0.0)), 0L).isEmpty())
        assertEquals("0h 1m remaining", insulinRemainingLabel(1))
        assertEquals("0h 0m remaining", insulinRemainingLabel(-1))
        assertEquals(listOf(120,150,180,210,240,270,300,330,360), insulinActionOptions)
    }
}
