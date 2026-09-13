package com.geoknoesis.kastor.ontoquality.metrics.compute

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleDetectorRegressionTest {
    @Test fun `deep taxonomy traverses without recursion`() {
        val nodes = (0 until 50_000).map(Int::toString)
        val edges = nodes.zipWithNext().associate { (a,b) -> a to setOf(b) }.toMutableMap()
        assertTrue(CycleDetector.cycleParticipants(nodes, edges).isEmpty())
        edges[nodes.last()] = setOf(nodes.first())
        assertEquals(nodes.toSet(), CycleDetector.cycleParticipants(nodes, edges))
    }
}
