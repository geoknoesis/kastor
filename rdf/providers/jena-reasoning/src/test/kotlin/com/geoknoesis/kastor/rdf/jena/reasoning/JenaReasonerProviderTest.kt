package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenaReasonerProviderTest {
    private val provider = JenaReasonerProvider()
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

    private fun turtle(text: String): RdfGraph = JenaProvider().parseGraph(text.byteInputStream(), "TURTLE")

    private val prefixes = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    @Test
    fun `capabilities are honest`() {
        val caps = provider.getCapabilities()
        assertEquals(setOf(ReasonerType.RDFS, ReasonerType.OWL_RL, ReasonerType.CUSTOM), caps.supportedTypes)
        assertFalse(caps.supportsIncrementalReasoning)
        assertTrue(caps.supportsCustomRules)
        assertFalse(provider.isSupported(ReasonerType.OWL_EL))
        assertThrows(IllegalArgumentException::class.java) { provider.createReasoner(ReasonerConfig.owlEl()) }
        assertThrows(IllegalArgumentException::class.java) { JenaReasoner(ReasonerConfig.owlDl()) }
    }

    @Test
    fun `RDFS infers types through subclass chains and preserves literal forms`() {
        val graph = turtle("$prefixes\nex:A rdfs:subClassOf ex:B . ex:B rdfs:subClassOf ex:C . ex:x a ex:A ; ex:v \"1.50\"^^xsd:decimal .")
        val result = provider.createReasoner(ReasonerConfig.rdfs()).reason(graph)
        assertTrue(RdfTriple(Iri("http://example.org/x"), type, Iri("http://example.org/C")) in result.inferredTriples)
        assertTrue(result.consistencyCheck.isConsistent)
        assertTrue(result.inferredTriples.none { it in graph.getTriples() }, "asserted triples are not inferred")
    }

    @Test
    fun `OWL RL detects a disjointness violation`() {
        val graph = turtle("$prefixes\nex:A a owl:Class ; owl:disjointWith ex:B . ex:B a owl:Class . ex:i a ex:A , ex:B .")
        val reasoner = provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_RL))
        assertFalse(reasoner.isConsistent(graph), "an individual in two disjoint classes is inconsistent")
        val result = reasoner.reason(graph)
        assertFalse(result.consistencyCheck.isConsistent)
        assertTrue(result.consistencyCheck.inconsistencies.isNotEmpty())
        assertFalse(reasoner.validateOntology(graph).isValid)

        val fine = turtle("$prefixes\nex:A a owl:Class ; owl:disjointWith ex:B . ex:B a owl:Class . ex:i a ex:A .")
        assertTrue(reasoner.isConsistent(fine))
    }

    @Test
    fun `RDFS validation flags ill-typed range values`() {
        val graph = turtle("$prefixes\nex:age rdfs:range xsd:integer . ex:x ex:age \"forty\"^^xsd:integer .")
        assertFalse(provider.createReasoner(ReasonerConfig.rdfs()).isConsistent(graph))
    }

    @Test
    fun `custom rules are applied and required`() {
        val rule = CustomRule(
            name = "parentOf",
            pattern = "(?a <http://example.org/childOf> ?b)",
            conclusion = "(?b <http://example.org/parentOf> ?a)",
        )
        val reasoner = provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.CUSTOM, customRules = listOf(rule)))
        val inferred = reasoner.getInferredTriples(turtle("$prefixes\nex:kid ex:childOf ex:mum ."))
        assertEquals(
            listOf(RdfTriple(Iri("http://example.org/mum"), Iri("http://example.org/parentOf"), Iri("http://example.org/kid"))),
            inferred,
        )
        assertThrows(IllegalArgumentException::class.java) { provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.CUSTOM)) }
    }

    @Test
    fun `registry prefers Jena over the memory fallback for RDFS`() {
        assertEquals("jena", ReasonerRegistry.findProviderForType(ReasonerType.RDFS)?.getType())
    }

    @Test
    fun `directional and triple-term objects survive conversion`() {
        val rtl = LangString("abc", "ar", Direction.RTL)
        val s = Iri("http://example.org/s")
        val graph = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(
            RdfTriple(s, Iri("http://example.org/label"), rtl),
            RdfTriple(Iri("http://example.org/p"), Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"), Iri("http://example.org/q")),
            RdfTriple(s, Iri("http://example.org/p"), TypedLiteral("007", XSD.integer)),
        ))
        val inferred = provider.createReasoner(ReasonerConfig.rdfs()).getInferredTriples(graph)
        assertTrue(RdfTriple(s, Iri("http://example.org/q"), TypedLiteral("007", XSD.integer)) in inferred, "$inferred")
        assertTrue(inferred.none { (it.obj as? LangString)?.lang == "ar" && it.obj != rtl }, "direction must not be dropped")
    }
}
