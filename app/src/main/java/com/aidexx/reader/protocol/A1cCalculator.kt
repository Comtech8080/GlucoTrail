package com.aidexx.reader.protocol

data class GmiEstimate(
    val averageMgDl: Double,
    val averageMmol: Double,
    val gmiPercent: Double
)

object A1cCalculator {
    private const val MG_DL_PER_MMOL_L = 18.0

    /**
     * Published CGM Glucose Management Indicator equation:
     * GMI (%) = 3.31 + 0.02392 × mean glucose (mg/dL)
     *
     * GMI is an estimate derived from CGM mean glucose. It is not a laboratory
     * HbA1c measurement and may differ from a measured A1C.
     */
    fun fromAverageMgDl(averageMgDl: Double): GmiEstimate {
        require(averageMgDl > 0.0)
        val averageMmol = averageMgDl / MG_DL_PER_MMOL_L
        val gmi = 3.31 + 0.02392 * averageMgDl
        return GmiEstimate(
            averageMgDl = averageMgDl,
            averageMmol = averageMmol,
            gmiPercent = gmi
        )
    }

    fun fromAverageMmol(averageMmol: Double): GmiEstimate {
        require(averageMmol > 0.0)
        return fromAverageMgDl(averageMmol * MG_DL_PER_MMOL_L)
    }
}
