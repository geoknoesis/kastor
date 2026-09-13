package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasonerProvider
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryReasonerRdfsTest {
    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val subPropertyOf = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
    private val domain = Iri("http://www.w3.org/2000/01/rdf-schema#domain")
    private val range = Iri("http://www.w3.org/2000/01/rdf-schema#range")

    private fun inferred(vararg triples: RdfTriple): Set<RdfTriple> =
        MemoryReasoner(ReasonerConfig.rdfs()).reason(MemoryGraph(triples.toList())).inferredTriples.toSet()

    @Test
    fun `rdfs9 propagates types along a multi-step subclass chain`() {
        val result = inferred(
            RdfTriple(iri("x"), type, iri("A")),
            RdfTriple(iri("A"), subClassOf, iri("B")),
            RdfTriple(iri("B"), subClassOf, iri("C")),
            RdfTriple(iri("C"), subClassOf, iri("D")),
        )
        assertTrue(RdfTriple(iri("x"), type, iri("B")) in result)
        assertTrue(RdfTriple(iri("x"), type, iri("D")) in result)
        assertTrue(RdfTriple(iri("A"), subClassOf, iri("D")) in result, "rdfs11 must reach the fixpoint")
    }

    @Test
    fun `rdfs5 and rdfs7 inherit properties transitively`() {
        val result = inferred(
            RdfTriple(iri("x"), iri("p"), iri("y")),
            RdfTriple(iri("p"), subPropertyOf, iri("q")),
            RdfTriple(iri("q"), subPropertyOf, iri("r")),
        )
        assertTrue(RdfTriple(iri("p"), subPropertyOf, iri("r")) in result)
        assertTrue(RdfTriple(iri("x"), iri("q"), iri("y")) in result)
        assertTrue(RdfTriple(iri("x"), iri("r"), iri("y")) in result)
    }

    @Test
    fun `rdfs2 and rdfs3 cover blank nodes and chain with subclass reasoning`() {
        val b = BlankNode("b1")
        val result = inferred(
            RdfTriple(iri("x"), iri("p"), b),
            RdfTriple(iri("p"), domain, iri("D")),
            RdfTriple(iri("p"), range, iri("R")),
            RdfTriple(iri("R"), subClassOf, iri("S")),
        )
        assertTrue(RdfTriple(iri("x"), type, iri("D")) in result)
        assertTrue(RdfTriple(b, type, iri("R")) in result, "range must apply to blank-node objects")
        assertTrue(RdfTriple(b, type, iri("S")) in result, "range-derived types must propagate through subClassOf")
    }

    @Test
    fun `range is not applied to literal objects and asserted triples are not reported`() {
        val asserted = RdfTriple(iri("x"), type, iri("A"))
        val result = inferred(
            asserted,
            RdfTriple(iri("x"), iri("p"), string("v")),
            RdfTriple(iri("p"), range, iri("R")),
        )
        assertFalse(asserted in result)
        assertTrue(result.none { it.subject !is Iri && it.subject !is BlankNode })
        assertTrue(result.isEmpty(), "no entailment expected: $result")
    }

    @Test
    fun `disabled rules are not applied`() {
        val config = ReasonerConfig(reasonerType = ReasonerType.RDFS, enabledRules = setOf(ReasoningRule.RDFS_DOMAIN))
        val result = MemoryReasoner(config).reason(MemoryGraph(listOf(
            RdfTriple(iri("x"), type, iri("A")),
            RdfTriple(iri("A"), subClassOf, iri("B")),
        ))).inferredTriples
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ill-typed literals make the graph inconsistent`() {
        val reasoner = MemoryReasoner(ReasonerConfig.rdfs())
        assertTrue(reasoner.isConsistent(MemoryGraph(listOf(RdfTriple(iri("x"), iri("p"), TypedLiteral("42", XSD.integer))))))
        val bad = MemoryGraph(listOf(RdfTriple(iri("x"), iri("p"), TypedLiteral("forty-two", XSD.integer))))
        assertFalse(reasoner.isConsistent(bad))
        assertFalse(reasoner.validateOntology(bad).isValid)
    }

    @Test
    fun `registry prefers the highest priority provider deterministically`() {
        val preferred = object : RdfReasonerProvider {
            override fun getType() = "zz-test-preferred"
            override val name = "test"
            override val version = "0"
            override fun createReasoner(config: ReasonerConfig): RdfReasoner = MemoryReasoner(config)
            override fun getCapabilities() = ReasonerCapabilities(setOf(ReasonerType.RDFS))
            override fun getSupportedTypes() = listOf(ReasonerType.RDFS)
            override fun isSupported(type: ReasonerType) = type == ReasonerType.RDFS
            override fun priority() = 10
        }
        assertEquals("memory", ReasonerRegistry.findProviderForType(ReasonerType.RDFS)?.getType())
        ReasonerRegistry.register(preferred)
        try {
            repeat(20) { assertEquals("zz-test-preferred", ReasonerRegistry.findProviderForType(ReasonerType.RDFS)?.getType()) }
            assertEquals("zz-test-preferred", ReasonerRegistry.getProviders().first().getType())
        } finally {
            ReasonerRegistry.unregister(preferred.getType())
        }
        assertTrue(MemoryReasonerProvider().priority() < 0)
    }

    @Test
    fun `DL reasoner types default to non-streaming configurations`() {
        assertFalse(ReasonerConfig(reasonerType = ReasonerType.HERMIT).streamingMode)
        assertFalse(ReasonerConfig.owlDl().streamingMode)
        assertFalse(ReasonerConfig.forType(ReasonerType.HERMIT).streamingMode)
        assertTrue(ReasonerConfig(reasonerType = ReasonerType.RDFS).streamingMode)
    }
}
