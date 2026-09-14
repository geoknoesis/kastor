package com.geoknoesis.kastor.ontoquality.metrics.compute

/**
 * OQuaRE 1–5 scoring bands (Duque-Ramos et al. 2014, scale table; reproduced in Duque-Ramos et al. 2016, Table 4;
 * 5 = best), plus the documented bands of the Kastor-adapted variants (`*Kastor`).
 */
internal object OquareScoring {
    fun scoreDIT(v: Number): Int = scoreOver8Pattern(v.toDouble())

    fun scoreNAC(v: Number): Int = scoreOver8Pattern(v.toDouble())

    fun scoreCBO(v: Number): Int = scoreOver8Pattern(v.toDouble())

    fun scoreLCOM(v: Number): Int = scoreOver8Pattern(v.toDouble())

    fun scoreNOM(v: Number): Int = scoreOver8Pattern(v.toDouble())

    fun scoreNOC(v: Number): Int = scoreNOCRFC(v.toDouble())

    fun scoreRFC(v: Number): Int = scoreNOCRFC(v.toDouble())

    fun scoreWMC(v: Number): Int {
        val d = v.toDouble()
        return when {
            d > 15.0 -> 1
            d > 11.0 -> 2
            d > 8.0 -> 3
            d > 5.0 -> 4
            else -> 5
        }
    }

    /** Published TMOnto band: > 8 → 1, (6, 8] → 2, (4, 6] → 3, (2, 4] → 4, ≤ 2 → 5. */
    fun scoreTM(v: Number): Int = scoreOver8Pattern(v.toDouble())

    /** NOCOntoKastor uses the NOCOnto bands: > 12 → 1, (8, 12] → 2, (6, 8] → 3, (3, 6] → 4, ≤ 3 → 5. */
    fun scoreNOCKastor(v: Number): Int = scoreNOCRFC(v.toDouble())

    /** CBOOntoKastor uses the CBOOnto bands: > 8 → 1, (6, 8] → 2, (4, 6] → 3, (2, 4] → 4, ≤ 2 → 5. */
    fun scoreCBOKastor(v: Number): Int = scoreOver8Pattern(v.toDouble())

    /**
     * TMOntoKastor: 0 (no multiple inheritance) → 5, (0, 2] → 4, (2, 4] → 3, (4, 8] → 2, > 8 → 1.
     *
     * Not an OQuaRE band: TMOntoKastor is ≥ 2 whenever any class has several direct parents, so the OQuaRE band
     * (≤ 2 → 5) could never distinguish a tangled hierarchy (e.g. a diamond, value 2) from one without tangling.
     */
    fun scoreTMKastor(v: Number): Int {
        val d = v.toDouble()
        return when {
            d <= 0.0 -> 5
            d <= 2.0 -> 4
            d <= 4.0 -> 3
            d <= 8.0 -> 2
            else -> 1
        }
    }

    /**
     * Richness metrics (RROnto, INROnto, AROnto, CROnto, ANOnto, PROnto). OQuaRE bands these as percentages:
     * [0, 20%] → 1, (20, 40%] → 2, (40, 60%] → 3, (60, 80%] → 4, > 80% → 5. Per-class averages (INROnto,
     * AROnto, CROnto, ANOnto) are unbounded above; any value above 0.8 — including values greater than 1,
     * e.g. several annotations per class — falls into the top band, as in the OQuaRE scale.
     */
    fun scoreRichness(ratio: Double): Int =
        when {
            ratio <= 0.20 -> 1
            ratio <= 0.40 -> 2
            ratio <= 0.60 -> 3
            ratio <= 0.80 -> 4
            else -> 5
        }

    private fun scoreOver8Pattern(d: Double): Int =
        when {
            d > 8.0 -> 1
            d > 6.0 -> 2
            d > 4.0 -> 3
            d > 2.0 -> 4
            else -> 5
        }

    private fun scoreNOCRFC(d: Double): Int =
        when {
            d > 12.0 -> 1
            d > 8.0 -> 2
            d > 6.0 -> 3
            d > 3.0 -> 4
            else -> 5
        }
}
