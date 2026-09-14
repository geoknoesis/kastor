package com.geoknoesis.kastor.ontoquality.embed

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilaritySearchLimitsScalingTest {
    @Test
    fun `small vocabularies keep the fixed defaults`() {
        assertEquals(SimilaritySearchLimits(), SimilaritySearchLimits.forEntityCount(0))
        assertEquals(SimilaritySearchLimits(), SimilaritySearchLimits.forEntityCount(10))
        assertEquals(50_000_000L, SimilaritySearchLimits.forEntityCount(9_000).maxDistanceEvaluations)
    }

    @Test
    fun `work budget and deadline scale with the worst-case exact search cost`() {
        val scaled = SimilaritySearchLimits.forEntityCount(20_000)
        // n(n-1)/2 query evaluations + n * bitLength(n) for tree construction: 199,990,000 + 20,000 * 15.
        assertEquals(200_290_000L, scaled.maxDistanceEvaluations)
        // 5,000 distance evaluations per millisecond, never below the 30 s default.
        assertEquals(Duration.ofMillis(40_058), scaled.timeout)
        assertEquals(1_000_000, scaled.maxPairs)
        // 10,000 entities need more work than the default but still fit in the default deadline.
        val medium = SimilaritySearchLimits.forEntityCount(10_000)
        assertEquals(50_135_000L, medium.maxDistanceEvaluations)
        assertEquals(Duration.ofSeconds(30), medium.timeout)
    }

    @Test
    fun `scaling does not overflow`() {
        val huge = SimilaritySearchLimits.forEntityCount(Int.MAX_VALUE)
        assertTrue(huge.maxDistanceEvaluations > 50_000_000L)
        assertTrue(huge.timeout > Duration.ofSeconds(30))
    }
}
