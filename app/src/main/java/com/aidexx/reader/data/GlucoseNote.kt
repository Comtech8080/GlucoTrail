package com.aidexx.reader.data

data class GlucoseNote(
    val id: Long = 0L,
    val serial: String,
    val timestampMs: Long,
    val fastInsulinUnits: Double = 0.0,
    val slowInsulinUnits: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val noteText: String = "",
    val photoPaths: List<String> = emptyList(),
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val pulse: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val bodyFatPercent: Double? = null,
    val fastInsulinActionMinutes: Int? = null
) {
    val hasFastInsulin: Boolean
        get() = fastInsulinUnits > 0.0

    val hasSlowInsulin: Boolean
        get() = slowInsulinUnits > 0.0

    val hasCarbs: Boolean
        get() = carbsGrams > 0.0

    val hasText: Boolean
        get() = noteText.isNotBlank()

    val hasPhotos: Boolean
        get() = photoPaths.isNotEmpty()

    val hasContent: Boolean
        get() =
            hasFastInsulin ||
                hasSlowInsulin ||
                hasCarbs ||
                hasText ||
                hasPhotos || systolic != null || weightKg != null || heightCm != null || bodyFatPercent != null
}
