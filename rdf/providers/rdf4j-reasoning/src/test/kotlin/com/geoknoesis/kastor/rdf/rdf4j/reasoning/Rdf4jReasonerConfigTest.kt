package com.geoknoesis.kastor.rdf.rdf4j.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** [ReasonerConfig] options are honoured (or clearly rejected) by the RDF4J reasoner. */
class Rdf4jReasonerConfigTest {
    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val domain = Iri("http://www.w3.org/2000/01/rdf-schema#domain")

    private val schema = MemoryGraph(listOf(
        RdfTriple(iri("A"), subClassOf, iri("B")),
        RdfTriple(iri("p"), domain, iri("D")),
        RdfTriple(iri("x"), type, iri("A")),
        RdfTriple(iri("x"), iri("p"), iri("y")),
    ))

    private fun chain(classes: Int, instances: Int) = MemoryGraph(
        (0 until classes).map { RdfTriple(iri("C$it"), subClassOf, iri("C${it + 1}")) } +
            (0 until instances).map { RdfTriple(iri("i$it"), type, iri("C0")) },
    )

    @Test
    fun `rule subsets are rejected because the RDF4J inferencer cannot select rules`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Rdf4jReasoner(ReasonerConfig(reasonerType = ReasonerType.RDFS, enabledRules = setOf(ReasoningRule.RDFS_SUBCLASS)))
        }
        assertTrue(error.message!!.contains("enabledRules"), error.message)
        // The complete RDFS rule set (also inside a larger set) is accepted.
        Rdf4jReasoner(ReasonerConfig.default())
        Rdf4jReasoner(ReasonerConfig.rdfs())
    }

    @Test
    fun `axiomatic vocabulary triples are dropped by default`() {
        val inferred = Rdf4jReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema)
        assertTrue(RdfTriple(iri("x"), type, iri("B")) in inferred)
        assertTrue(RdfTriple(iri("x"), type, iri("D")) in inferred)
        assertTrue(inferred.none { (it.subject as? Iri)?.value?.startsWith("http://www.w3.org/") == true }, "$inferred")
        assertTrue(inferred.containsAll(MemoryReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema)))

        val withAxioms = Rdf4jReasoner(ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))).getInferredTriples(schema)
        assertTrue(withAxioms.any { (it.subject as? Iri)?.value?.startsWith("http://www.w3.org/") == true })
    }

    @Test
    fun `materializationThreshold bounds the inferred triples`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Rdf4jReasoner(ReasonerConfig.rdfs().copy(materializationThreshold = 10)).reason(chain(10, 10))
        }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
    }

    @Test
    fun `timeout is enforced`() {
        val start = System.nanoTime()
        val error = assertThrows(IllegalStateException::class.java) {
            Rdf4jReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(30), materializationThreshold = Long.MAX_VALUE)).reason(chain(300, 300))
        }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000)
    }

    @Test
    fun `OWL Micro is rejected with a clear error`() {
        val provider = Rdf4jReasonerProvider()
        assertFalse(provider.isSupported(ReasonerType.OWL_MICRO))
        val error = assertThrows(IllegalArgumentException::class.java) { provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO)) }
        assertTrue(error.message!!.contains("OWL_MICRO"), error.message)
    }
}
