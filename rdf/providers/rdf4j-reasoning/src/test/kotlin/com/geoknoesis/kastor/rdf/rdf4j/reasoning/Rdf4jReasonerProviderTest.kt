package com.geoknoesis.kastor.rdf.rdf4j.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Rdf4jReasonerProviderTest {
    private val provider = Rdf4jReasonerProvider()
    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val disjointWith = Iri("http://www.w3.org/2002/07/owl#disjointWith")

    @Test
    fun `only RDFS is supported and advertised`() {
        assertEquals(setOf(ReasonerType.RDFS), provider.getCapabilities().supportedTypes)
        assertFalse(provider.isSupported(ReasonerType.OWL_EL))
        assertThrows(IllegalArgumentException::class.java) { provider.createReasoner(ReasonerConfig.owlEl()) }
        assertThrows(IllegalArgumentException::class.java) { Rdf4jReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_RL)) }
    }

    @Test
    fun `RDFS closure propagates types along subclass chains`() {
        val graph = MemoryGraph(listOf(
            RdfTriple(iri("x"), type, iri("A")),
            RdfTriple(iri("A"), subClassOf, iri("B")),
            RdfTriple(iri("B"), subClassOf, iri("C")),
        ))
        val result = provider.createReasoner(ReasonerConfig.rdfs()).reason(graph)
        assertTrue(RdfTriple(iri("x"), type, iri("C")) in result.inferredTriples)
        assertTrue(result.inferredTriples.none { it in graph.getTriples() })
        assertTrue(result.consistencyCheck.isConsistent)
    }

    @Test
    fun `disjointness is outside RDFS and ill-typed literals are inconsistent`() {
        val reasoner = provider.createReasoner(ReasonerConfig.rdfs())
        // owl:disjointWith has no RDFS semantics: the graph is RDFS-consistent (OWL types are rejected above).
        assertTrue(reasoner.isConsistent(MemoryGraph(listOf(
            RdfTriple(iri("A"), disjointWith, iri("B")),
            RdfTriple(iri("i"), type, iri("A")),
            RdfTriple(iri("i"), type, iri("B")),
        ))))
        val bad = MemoryGraph(listOf(RdfTriple(iri("x"), iri("age"), TypedLiteral("forty", XSD.integer))))
        assertFalse(reasoner.isConsistent(bad))
        assertFalse(reasoner.reason(bad).consistencyCheck.isConsistent)
        assertFalse(reasoner.validateOntology(bad).isValid)
    }

    @Test
    fun `conversion keeps lexical forms and base direction`() {
        val rtl = LangString("abc", "ar", Direction.RTL)
        val graph = MemoryGraph(listOf(
            RdfTriple(iri("p"), Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"), iri("q")),
            RdfTriple(iri("s"), iri("p"), TypedLiteral("007", XSD.integer)),
            RdfTriple(iri("s"), iri("p"), rtl),
        ))
        val inferred = provider.createReasoner(ReasonerConfig.rdfs()).getInferredTriples(graph)
        assertTrue(RdfTriple(iri("s"), iri("q"), TypedLiteral("007", XSD.integer)) in inferred, "$inferred")
        assertTrue(RdfTriple(iri("s"), iri("q"), rtl) in inferred, "$inferred")
    }
}
