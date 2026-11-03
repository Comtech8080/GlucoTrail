package com.aidexx.reader.data

data class BloodPressureEntry(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    val note: String = ""
)

data class WeightEntry(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val weightKg: Double,
    val note: String = ""
)
