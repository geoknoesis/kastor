package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.reasoning.Rdf4jReasoner
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RDFS reasoners (Jena, RDF4J, memory; HermiT is OWL DL only) agree on what counts as an inference for data with
 * typed literals: axiomatic triples about the RDF/RDFS/OWL/XSD vocabulary alone (e.g. `xsd:integer a rdfs:Datatype`,
 * triggered by a literal in the data) are not reported, while entailments that involve the data's own terms are.
 */
class ReasonerAxiomParityTest {
    private val ex = "http://example.org/"
    private val vocabulary = listOf(
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "http://www.w3.org/2000/01/rdf-schema#",
        "http://www.w3.org/2002/07/owl#",
        "http://www.w3.org/2001/XMLSchema#",
    )
    private fun isVocabulary(term: Any) = term is Iri && vocabulary.any { term.value.startsWith(it) }
    private fun pureVocabulary(t: RdfTriple) = isVocabulary(t.subject) && isVocabulary(t.predicate) && isVocabulary(t.obj)

    private val data: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        ex:Person rdfs:subClassOf ex:Agent .
        ex:age rdfs:domain ex:Person ; rdfs:range xsd:integer .
        ex:name rdfs:subPropertyOf rdfs:label .
        ex:alice ex:age "42"^^xsd:integer ; ex:name "Alice" ; ex:height "1.7"^^xsd:decimal ; ex:born "1980-01-01"^^xsd:date .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    private val reasoners = mapOf(
        "jena" to { g: RdfGraph -> JenaReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
        "rdf4j" to { g: RdfGraph -> Rdf4jReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
        "memory" to { g: RdfGraph -> MemoryReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
    )

    @Test
    fun `no reasoner reports pure vocabulary axioms triggered by typed literals`() {
        for ((name, infer) in reasoners) {
            val axioms = infer(data).filter(::pureVocabulary)
            assertTrue(axioms.isEmpty(), "$name reports vocabulary axioms as inferences: $axioms")
        }
    }

    @Test
    fun `the reasoners agree on inferences about the data's own terms`() {
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val label = Iri("http://www.w3.org/2000/01/rdf-schema#label")
        val expected = setOf(
            RdfTriple(Iri(ex + "alice"), type, Iri(ex + "Person")),
            RdfTriple(Iri(ex + "alice"), type, Iri(ex + "Agent")),
            RdfTriple(Iri(ex + "alice"), label, com.geoknoesis.kastor.rdf.Literal("Alice")),
        )
        // Only the RDFS rules every reasoner implements (subclass, subproperty, domain, range); the memory reasoner
        // does not add rdfs:Resource / rdf:Property typing, so those are compared separately above.
        val core = reasoners.mapValues { (_, infer) ->
            infer(data).filter { !pureVocabulary(it) && (it.obj !is Iri || !isVocabulary(it.obj)) && it.predicate != Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf") && it.predicate != Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf") }.toSet()
        }
        for ((name, inferred) in core) assertTrue(inferred.containsAll(expected), "$name misses ${expected - inferred}")
        assertEquals(core.getValue("memory"), core.getValue("jena"), "jena vs memory")
        assertEquals(core.getValue("memory"), core.getValue("rdf4j"), "rdf4j vs memory")
    }

    @Test
    fun `includeAxiomaticTriples brings the vocabulary axioms back`() {
        val include = ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))
        assertTrue(JenaReasoner(include).getInferredTriples(data).any(::pureVocabulary))
        assertTrue(Rdf4jReasoner(include).getInferredTriples(data).any(::pureVocabulary))
    }
}
