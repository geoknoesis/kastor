package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class KastorMetricsProviderTest {

    private fun loadFixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().readText()

    @Test
    fun `provider produces importance scores in 0 to 1 range`() {
        val provider = KastorMetricsProvider()
        val graph = Rdf.parse(loadFixture("small-synthetic.ttl"), "TURTLE")
        val context = provider.compute(graph)
        context.entityImportance.values.forEach {
            assertTrue(it in 0.0..1.0, "importance score $it out of range")
        }
    }

    @Test
    fun `high-fanout class has higher importance than peripheral class`() {
        val provider = KastorMetricsProvider()
        val graph = Rdf.parse(loadFixture("hub-and-spokes.ttl"), "TURTLE")
        val context = provider.compute(graph)
        val hubScore = context.entityImportance["http://example.org/Hub"] ?: 0.0
        val peripheralScore = context.entityImportance["http://example.org/Peripheral"] ?: 0.0
        assertTrue(
            hubScore > peripheralScore,
            "Hub ($hubScore) should outrank Peripheral ($peripheralScore)",
        )
    }

    @Test
    fun `provider works on empty graph`() {
        val provider = KastorMetricsProvider()
        val graph = Rdf.graph {}
        val context = provider.compute(graph)
        assertTrue(context.entityImportance.isEmpty())
        assertTrue(context.summary.isNotEmpty())
    }
}
