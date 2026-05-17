package com.geoknoesis.kastor.ontoquality.metrics.compute

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

    fun scoreTM(v: Number): Int {
        val d = v.toDouble()
        return when {
            d > 8.0 -> 1
            d > 6.0 -> 2
            d > 4.0 -> 3
            d > 2.0 -> 4
            d > 1.0 -> 5
            else -> 5
        }
    }

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
