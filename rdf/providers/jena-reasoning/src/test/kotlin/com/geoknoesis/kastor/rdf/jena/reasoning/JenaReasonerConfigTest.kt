package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** [ReasonerConfig] options are honoured by the Jena reasoners, for parity with the memory reasoner. */
class JenaReasonerConfigTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private fun iri(local: String) = Iri(ex + local)
    private fun turtle(text: String): RdfGraph = JenaProvider().parseGraph(text.byteInputStream(), "TURTLE")

    private val prefixes = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <$ex> .
    """.trimIndent()

    private val schema = turtle("""
        $prefixes
        ex:A rdfs:subClassOf ex:B . ex:B rdfs:subClassOf ex:C .
        ex:p rdfs:domain ex:D ; rdfs:range ex:R ; rdfs:subPropertyOf ex:q .
        ex:x a ex:A ; ex:p ex:y .
    """.trimIndent())

    private fun chain(classes: Int, instances: Int): RdfGraph = turtle(buildString {
        appendLine(prefixes)
        for (c in 0 until classes) appendLine("ex:C$c rdfs:subClassOf ex:C${c + 1} .")
        for (i in 0 until instances) appendLine("ex:i$i a ex:C0 .")
    })

    @Test
    fun `a subset of RDFS rules applies exactly the selected rules, like the memory reasoner`() {
        for (rules in listOf(setOf(ReasoningRule.RDFS_SUBCLASS), setOf(ReasoningRule.RDFS_DOMAIN, ReasoningRule.RDFS_RANGE), setOf(ReasoningRule.RDFS_SUBPROPERTY))) {
            val config = ReasonerConfig(reasonerType = ReasonerType.RDFS, enabledRules = rules)
            val jena = JenaReasoner(config).getInferredTriples(schema).toSet()
            val memory = MemoryReasoner(config).getInferredTriples(schema).toSet()
            assertEquals(memory, jena, "rules $rules")
        }
        val subclassOnly = JenaReasoner(ReasonerConfig(reasonerType = ReasonerType.RDFS, enabledRules = setOf(ReasoningRule.RDFS_SUBCLASS)))
            .getInferredTriples(schema)
        assertTrue(RdfTriple(iri("x"), type, iri("C")) in subclassOnly)
        assertFalse(RdfTriple(iri("x"), type, iri("D")) in subclassOnly, "domain rule is disabled")
    }

    @Test
    fun `full RDFS drops axiomatic vocabulary triples by default`() {
        val inferred = JenaReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema)
        assertTrue(RdfTriple(iri("x"), type, iri("C")) in inferred)
        assertTrue(RdfTriple(iri("x"), type, iri("D")) in inferred)
        assertTrue(RdfTriple(iri("y"), type, iri("R")) in inferred)
        assertTrue(inferred.none { (it.subject as? Iri)?.value?.startsWith("http://www.w3.org/") == true }, "$inferred")
        // Every memory-reasoner entailment is also produced by Jena's full RDFS reasoner.
        assertTrue(inferred.containsAll(MemoryReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema)))

        val withAxioms = JenaReasoner(ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))).getInferredTriples(schema)
        assertTrue(withAxioms.any { (it.subject as? Iri)?.value?.startsWith("http://www.w3.org/") == true })
    }

    @Test
    fun `OWL reasoners reject rule selections they cannot apply`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JenaReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_RL, enabledRules = setOf(ReasoningRule.RDFS_SUBCLASS)))
        }
        assertTrue(error.message!!.contains("enabledRules"), error.message)
        assertThrows(IllegalArgumentException::class.java) {
            JenaReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO, enabledRules = setOf(ReasoningRule.RDFS_SUBCLASS)))
        }
    }

    @Test
    fun `materializationThreshold bounds the inferred triples`() {
        val config = ReasonerConfig.rdfs().copy(materializationThreshold = 10)
        val error = assertThrows(IllegalArgumentException::class.java) { JenaReasoner(config).reason(chain(10, 10)) }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
        assertTrue(JenaReasoner(ReasonerConfig.rdfs().copy(materializationThreshold = 10_000)).reason(chain(10, 10)).inferredTriples.size > 10)
    }

    @Test
    fun `timeout is enforced`() {
        val graph = chain(300, 300)
        val start = System.nanoTime()
        val error = assertThrows(IllegalStateException::class.java) {
            JenaReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(50), materializationThreshold = Long.MAX_VALUE)).reason(graph)
        }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
    }

    @Test
    fun `OWL Micro materialises subclass and inverse-property inferences`() {
        val graph = turtle("""
            $prefixes
            ex:Dog rdfs:subClassOf ex:Animal .
            ex:hasOwner owl:inverseOf ex:owns .
            ex:rex a ex:Dog ; ex:hasOwner ex:alice .
        """.trimIndent())
        val provider = JenaReasonerProvider()
        assertTrue(provider.isSupported(ReasonerType.OWL_MICRO))
        val inferred = provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO)).getInferredTriples(graph)
        assertTrue(RdfTriple(iri("rex"), type, iri("Animal")) in inferred, "$inferred")
        assertTrue(RdfTriple(iri("alice"), iri("owns"), iri("rex")) in inferred, "$inferred")
    }
}
