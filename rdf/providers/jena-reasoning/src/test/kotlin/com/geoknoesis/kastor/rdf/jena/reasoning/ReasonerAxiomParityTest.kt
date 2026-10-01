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
 * The RDFS reasoners (Jena, RDF4J, memory; HermiT is OWL DL only) agree on what counts as an inference: triples that
 * hold without any data (the closure of the empty graph, including datatype axioms such as
 * `xsd:integer a rdfs:Class` that an engine only materialises once the data mentions the datatype) are not reported,
 * while everything that follows from the data is, also when it relates vocabulary terms only.
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

    /** Schema the user asserts about vocabulary terms: an `owl:` property class hierarchy below `rdf:Property`. */
    private val vocabularySchema: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        owl:ObjectProperty rdfs:subClassOf rdf:Property .
        owl:FunctionalProperty rdfs:subClassOf owl:ObjectProperty .
        ex:knows a owl:FunctionalProperty ; rdfs:range xsd:string .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    @Test
    fun `inferences from user-asserted schema about vocabulary terms are reported by every reasoner`() {
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val rdfProperty = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")
        val objectProperty = Iri("http://www.w3.org/2002/07/owl#ObjectProperty")
        val functionalProperty = Iri("http://www.w3.org/2002/07/owl#FunctionalProperty")
        val expected = setOf(
            // Relates vocabulary terms only, but follows from the two asserted subclass statements.
            RdfTriple(functionalProperty, subClassOf, rdfProperty),
            RdfTriple(Iri(ex + "knows"), type, objectProperty),
            RdfTriple(Iri(ex + "knows"), type, rdfProperty),
        )
        // The memory reasoner implements the subclass, subproperty, domain and range rules only; Jena and RDF4J also
        // type every resource (rdfs:Resource, rdfs:Class) and add reflexive subclass / subproperty statements.
        val structuralTypes = setOf(Iri("http://www.w3.org/2000/01/rdf-schema#Resource"), Iri("http://www.w3.org/2000/01/rdf-schema#Class"))
        fun structural(t: RdfTriple) = (t.predicate == type && t.obj in structuralTypes) ||
            (t.predicate == subClassOf && (t.obj == t.subject || t.obj in structuralTypes)) ||
            (t.predicate == Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf") && t.obj == t.subject)
        val results = reasoners.mapValues { (_, infer) -> infer(vocabularySchema).toSet() }
        for ((name, inferred) in results) {
            assertTrue(inferred.containsAll(expected), "$name misses ${expected - inferred}")
            assertEquals(expected, inferred.filterNot(::structural).toSet(), "$name: inferences beyond the structural ones")
            // Datatype axioms are not inferences, although the schema mentions xsd:string.
            val datatypeAxioms = inferred.filter { it.subject == Iri("http://www.w3.org/2001/XMLSchema#string") }
            assertTrue(datatypeAxioms.isEmpty(), "$name reports datatype axioms: $datatypeAxioms")
        }
        // What Jena and RDF4J derive about the owl: classes from the asserted schema is kept as well.
        for (name in listOf("jena", "rdf4j")) {
            assertTrue(RdfTriple(objectProperty, type, Iri("http://www.w3.org/2000/01/rdf-schema#Class")) in results.getValue(name), name)
        }
    }

    @Test
    fun `includeAxiomaticTriples brings the vocabulary axioms back`() {
        val include = ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))
        assertTrue(JenaReasoner(include).getInferredTriples(data).any(::pureVocabulary))
        assertTrue(Rdf4jReasoner(include).getInferredTriples(data).any(::pureVocabulary))
    }
}
