package com.aidexx.reader

import com.aidexx.reader.data.BloodPressureEntry

internal enum class BpCategory(val label: String, val argb: Long) {
    LOW("Low", 0xFF5BC5DF),
    NORMAL("Normal", 0xFF7CDE96),
    ELEVATED("Elevated", 0xFFFFD75E),
    STAGE_ONE("High · Stage 1", 0xFFFFAD65),
    STAGE_TWO("High · Stage 2", 0xFFF4649A)
}

// Adult AHA reading ranges, not a diagnosis:
// https://www.heart.org/en/health-topics/high-blood-pressure/understanding-blood-pressure-readings
// High values take priority when the two measurements fall in different categories.
internal fun bpCategory(systolic: Int, diastolic: Int): BpCategory = when {
    systolic >= 140 || diastolic >= 90 -> BpCategory.STAGE_TWO
    systolic >= 130 || diastolic >= 80 -> BpCategory.STAGE_ONE
    systolic < 90 || diastolic < 60 -> BpCategory.LOW
    systolic >= 120 -> BpCategory.ELEVATED
    else -> BpCategory.NORMAL
}

internal fun bpWindow(entries: List<BloodPressureEntry>, days: Int, nowMs: Long): List<BloodPressureEntry> {
    val start = nowMs - days.toLong() * 86_400_000L
    return entries.filter { it.timestampMs in start..nowMs }.sortedBy { it.timestampMs }
}

// Largest remainder rounding keeps the displayed legend total exactly 100%.
internal fun bpPercentages(counts: List<Int>): List<Int> {
    val total = counts.sum()
    if (total == 0) return counts.map { 0 }
    val exact = counts.map { it * 100.0 / total }
    val rounded = exact.map { it.toInt() }.toMutableList()
    val remaining = 100 - rounded.sum()
    exact.indices.sortedByDescending { exact[it] - rounded[it] }.take(remaining).forEach { rounded[it]++ }
    return rounded
}
