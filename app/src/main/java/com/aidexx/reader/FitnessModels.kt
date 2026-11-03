package com.aidexx.reader

data class MachineLog(val id: String, val name: String, val category: String,
    val weight: String = "0", val sets: String = "3", val reps: String = "10",
    val unit: String = "lb", val enabled: Boolean = true, val done: Boolean = false,
    val completedAt: Long = 0, val previous: String = "",
    val treadmill: Boolean = false, val minutes: String = "20", val speed: String = "3",
    val incline: String = "0", val distance: String = "1", val distanceUnit: String = "mi", val useEditedValues: Boolean = false) {
    fun valid() = if(treadmill) validCardioNumber(minutes, 0.1, 1440.0) &&
        validCardioNumber(speed, 0.1, 100.0) && validCardioNumber(incline, -15.0, 50.0) &&
        validCardioNumber(distance, 0.0, 1000.0) else weight.toDoubleOrNull()?.let { it.isFinite() && it >= 0 && it <= 10000 } == true &&
        (sets.toIntOrNull() ?: 0) in 1..100 && (reps.toIntOrNull() ?: 0) in 1..1000
    fun summary() = if(treadmill) "$minutes min · $distance $distanceUnit · $speed ${if(distanceUnit=="mi") "mph" else "km/h"} · $incline% incline" else "$weight $unit · $sets sets × $reps reps"
}
data class MachineWorkout(val startedAt: Long, val endedAt: Long, val machines: List<MachineLog>) {
    val totalSets get() = machines.filterNot { it.treadmill }.sumOf { it.sets.toIntOrNull() ?: 0 }
    val totalReps get() = machines.filterNot { it.treadmill }.sumOf { (it.sets.toIntOrNull() ?: 0) * (it.reps.toIntOrNull() ?: 0) }
}
fun finishedMachineWorkout(start: Long, end: Long, machines: List<MachineLog>): MachineWorkout? {
    val completed = machines.filter { it.enabled && it.done && it.valid() }
    return if(start <= 0 || end < start || completed.isEmpty()) null else MachineWorkout(start, end, completed)
}
fun nextMachineWorkout(machines: List<MachineLog>, history: List<MachineWorkout>): List<MachineLog> = machines.map { m ->
    val last = history.firstNotNullOfOrNull { w -> w.machines.find { it.id == m.id } }
    if(last == null || m.useEditedValues) m.copy(done=false, completedAt=0)
    else m.copy(weight=last.weight, sets=last.sets, reps=last.reps, unit=last.unit,
        done=false, completedAt=0, previous=last.summary(), minutes=last.minutes, speed=last.speed,
        incline=last.incline, distance=last.distance, distanceUnit=last.distanceUnit)
}
fun defaultMachines(): List<MachineLog> = listOf(
    "Chest Press" to "Chest", "Lat Pulldown" to "Back", "Seated Row" to "Back",
    "Shoulder Press" to "Shoulders", "Pec Fly" to "Chest", "Biceps Curl" to "Arms",
    "Triceps Press" to "Arms", "Leg Press" to "Legs", "Leg Extension" to "Legs",
    "Leg Curl" to "Legs", "Hip Abductor" to "Hips", "Hip Adductor" to "Hips",
    "Ab Crunch" to "Core", "Back Extension" to "Back", "Calf Raise" to "Legs"
).mapIndexed { i, (name, category) -> MachineLog("machine-$i", name, category) }

fun MachineLog.inFitnessUnit(target: String): MachineLog {
    require(target == "lb" || target == "kg")
    if(unit == target) return this
    val number = weight.toDoubleOrNull() ?: return this
    if(!number.isFinite()) return this
    val converted = if(target == "kg") number * 0.45359237 else number / 0.45359237
    val formatted = java.math.BigDecimal.valueOf(converted).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return copy(weight=formatted, unit=target)
}

private fun validCardioNumber(value: String, min: Double, max: Double) =
    value.toDoubleOrNull()?.let { it.isFinite() && it in min..max } == true
fun treadmillMachine() = MachineLog("treadmill", "Treadmill", "Cardio", treadmill=true)
fun MachineLog.inDistanceUnit(target: String): MachineLog {
    require(target == "mi" || target == "km")
    if(!treadmill || distanceUnit == target) return this
    fun convert(value: String): String {
        val n = value.toDoubleOrNull() ?: return value
        if(!n.isFinite()) return value
        val factor = if(target == "km") 1.609344 else 1.0 / 1.609344
        return java.math.BigDecimal.valueOf(n * factor).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
    return copy(distance=convert(distance), speed=convert(speed), distanceUnit=target)
}
