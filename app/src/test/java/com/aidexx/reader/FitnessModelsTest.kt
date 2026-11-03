package com.aidexx.reader

import org.junit.Assert.*
import org.junit.Test

class FitnessModelsTest {
    @Test fun editsOverridePrefillAndRemovalDoesNotChangeHistory() {
        val original = defaultMachines().first().copy(weight="40", done=true)
        val history = listOf(MachineWorkout(1, 2, listOf(original)))
        val edited = original.copy(name="My chest press", weight="60", sets="4", useEditedValues=true)
        val next = nextMachineWorkout(listOf(edited), history).single()
        assertEquals("60", next.weight)
        assertEquals("4", next.sets)
        assertEquals("My chest press", next.name)
        assertFalse(next.done)
        assertEquals("40", history.single().machines.single().weight)
        assertTrue(nextMachineWorkout(emptyList(), history).isEmpty())
    }

    @Test fun treadmillValidationTotalsPrefillAndUnits() {
        val treadmill = treadmillMachine().copy(done=true, minutes="30", speed="3.5", distance="1.75", incline="4")
        assertTrue(treadmill.valid())
        assertFalse(treadmill.copy(minutes="0").valid())
        assertFalse(treadmill.copy(speed="NaN").valid())
        assertFalse(treadmill.copy(incline="51").valid())
        val workout = finishedMachineWorkout(1, 2, listOf(treadmill))!!
        assertEquals(0, workout.totalSets)
        assertEquals(0, workout.totalReps)
        val next = nextMachineWorkout(listOf(treadmillMachine()), listOf(workout)).single()
        assertEquals("30", next.minutes)
        assertEquals("3.5", next.speed)
        assertEquals("1.75", next.distance)
        assertEquals("4", next.incline)
        assertFalse(next.done)
        val km = next.inDistanceUnit("km")
        assertEquals(2.816, km.distance.toDouble(), 0.001)
        assertEquals(5.633, km.speed.toDouble(), 0.001)
        assertEquals("mi", treadmill.distanceUnit)
        assertEquals("1.75", treadmill.distance)
    }

    @Test fun weightSettingsConvertWithoutChangingHistory() {
        val pounds = defaultMachines().first().copy(weight="100", done=true)
        val kg = pounds.inFitnessUnit("kg")
        assertEquals("45.359", kg.weight)
        assertEquals("kg", kg.unit)
        assertEquals(100.0, kg.inFitnessUnit("lb").weight.toDouble(), 0.002)
        assertEquals("100", pounds.weight)
        assertEquals("lb", pounds.unit)
        assertEquals(pounds, pounds.inFitnessUnit("lb"))
        val history = listOf(MachineWorkout(1, 2, listOf(pounds)))
        val next = nextMachineWorkout(listOf(kg), history).single().inFitnessUnit("kg")
        assertEquals("45.359", next.weight)
        assertEquals("100 lb · 3 sets × 10 reps", next.previous)
        assertFalse(next.done)
    }

    @Test fun defaultsAreUniqueAndGymIndependent() {
        val m = defaultMachines()
        assertEquals(15, m.size)
        assertEquals(15, m.map { it.id }.toSet().size)
        assertTrue(m.all { it.valid() && it.enabled && !it.done })
    }
    @Test fun rejectsInvalidAndNonFiniteInputs() {
        val m = defaultMachines().first()
        listOf("", "NaN", "Infinity", "-1", "10001").forEach { assertFalse(m.copy(weight=it).valid()) }
        assertFalse(m.copy(sets="0").valid())
        assertFalse(m.copy(reps="1.5").valid())
        assertTrue(m.copy(weight="12.5", sets="4", reps="12").valid())
    }
    @Test fun savesOnlyValidCompletedEnabledMachinesAndTimes() {
        val m = defaultMachines().first().copy(done=true, weight="80", sets="3", reps="10", completedAt=150)
        val w = finishedMachineWorkout(100, 200, listOf(m, m.copy(id="2", done=false), m.copy(id="3", enabled=false), m.copy(id="4", reps="")))!!
        assertEquals(listOf(m), w.machines)
        assertEquals(100L, w.startedAt)
        assertEquals(200L, w.endedAt)
        assertEquals(3, w.totalSets)
        assertEquals(30, w.totalReps)
        assertNull(finishedMachineWorkout(0, 200, listOf(m)))
        assertNull(finishedMachineWorkout(100, 99, listOf(m)))
        assertNull(finishedMachineWorkout(100, 200, emptyList()))
    }
    @Test fun nextWorkoutUsesLatestCompletedValuesAndPreservesManagement() {
        val m = defaultMachines().first()
        val older = MachineWorkout(1, 2, listOf(m.copy(weight="40", done=true)))
        val newer = MachineWorkout(3, 4, listOf(m.copy(weight="25.5", sets="4", reps="12", unit="kg", done=true)))
        val next = nextMachineWorkout(listOf(m.copy(weight="999", enabled=false, done=true, completedAt=5)), listOf(newer,older)).single()
        assertEquals("25.5", next.weight)
        assertEquals("4", next.sets)
        assertEquals("12", next.reps)
        assertEquals("kg", next.unit)
        assertFalse(next.done)
        assertFalse(next.enabled)
        assertEquals(0L, next.completedAt)
        assertEquals("25.5 kg · 4 sets × 12 reps", next.previous)
        assertEquals("40", older.machines.single().weight)
    }
}
