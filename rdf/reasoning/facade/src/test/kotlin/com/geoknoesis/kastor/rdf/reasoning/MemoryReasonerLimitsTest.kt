package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasonerProvider
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** The memory reasoner honours [ReasonerConfig.timeout] and [ReasonerConfig.materializationThreshold]. */
class MemoryReasonerLimitsTest {
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")

    private fun chain(classes: Int, instances: Int) = MemoryGraph(
        (0 until classes).map { RdfTriple(Iri("http://example.org/C$it"), subClassOf, Iri("http://example.org/C${it + 1}")) } +
            (0 until instances).map { RdfTriple(Iri("http://example.org/i$it"), type, Iri("http://example.org/C0")) },
    )

    @Test
    fun `materializationThreshold bounds the inferred triples`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MemoryReasoner(ReasonerConfig.rdfs().copy(materializationThreshold = 10)).reason(chain(10, 10))
        }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
        assertTrue(MemoryReasoner(ReasonerConfig.rdfs()).reason(chain(10, 10)).inferredTriples.size > 10)
    }

    @Test
    fun `timeout is enforced while computing the fixpoint`() {
        val start = System.nanoTime()
        val error = assertThrows(IllegalStateException::class.java) {
            MemoryReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(20), materializationThreshold = Long.MAX_VALUE))
                .reason(chain(400, 400))
        }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
    }

    @Test
    fun `OWL Micro is rejected with a clear error`() {
        val provider = MemoryReasonerProvider()
        assertTrue(!provider.isSupported(ReasonerType.OWL_MICRO))
        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO))
        }
        assertTrue(error.message!!.contains("OWL_MICRO"), error.message)
    }
}
