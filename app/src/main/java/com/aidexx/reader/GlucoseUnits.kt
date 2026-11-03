package com.aidexx.reader

import android.content.Context

enum class GlucoseUnit(
    val label: String
) {
    MMOL_L("mmol/L"),
    MG_DL("mg/dL");

    fun valueFromMmol(mmol: Double): Double =
        if (this == MMOL_L) mmol else mmol * MG_DL_PER_MMOL_L

    fun valueFromMgDl(mgDl: Double): Double =
        if (this == MMOL_L) mgDl / MG_DL_PER_MMOL_L else mgDl

    fun mmolFromDisplay(value: Double): Double =
        if (this == MMOL_L) value else value / MG_DL_PER_MMOL_L

    fun formatValueFromMmol(mmol: Double): String =
        if (this == MMOL_L) {
            String.format("%.1f", mmol)
        } else {
            String.format("%.0f", mmol * MG_DL_PER_MMOL_L)
        }

    fun formatValueFromMgDl(mgDl: Double): String =
        if (this == MMOL_L) {
            String.format("%.1f", mgDl / MG_DL_PER_MMOL_L)
        } else {
            String.format("%.0f", mgDl)
        }

    fun formatGlucoseFromMmol(mmol: Double): String =
        "${formatValueFromMmol(mmol)} $label"

    fun formatGlucoseFromMgDl(mgDl: Double): String =
        "${formatValueFromMgDl(mgDl)} $label"

    fun formatRateFromMgDlPerMinute(
        rateMgDlPerMinute: Double
    ): String =
        if (this == MMOL_L) {
            "${
                String.format(
                    "%+.2f",
                    rateMgDlPerMinute / MG_DL_PER_MMOL_L
                )
            } mmol/L/min"
        } else {
            "${
                String.format(
                    "%+.1f",
                    rateMgDlPerMinute
                )
            } mg/dL/min"
        }

    companion object {
        const val MG_DL_PER_MMOL_L = 18.0
    }
}

object GlucoseUnitPreferences {
    private const val PREFS_NAME = "aidexx_reader"
    private const val KEY_GLUCOSE_UNIT = "glucose_unit"

    fun load(context: Context): GlucoseUnit {
        val prefs =
            context.getSharedPreferences(PREFS_NAME, 0)

        return runCatching {
            GlucoseUnit.valueOf(
                prefs.getString(
                    KEY_GLUCOSE_UNIT,
                    GlucoseUnit.MMOL_L.name
                ) ?: GlucoseUnit.MMOL_L.name
            )
        }.getOrDefault(GlucoseUnit.MMOL_L)
    }
}
