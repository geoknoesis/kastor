package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasoningResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HermiT materializes OWL 2 DL-style entailments from RDF serializations loaded by OWL API.
 */
class HermitRdfReasonerTest {

    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

    @Test
    fun infersInstanceTypeAlongSubClass() {
        val turtle =
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A rdfs:subClassOf ex:B .
            ex:i rdf:type ex:A .
            """.trimIndent()

        val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
        val result = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph)

        assertTrue(result.consistencyCheck.isConsistent, "ontology should be consistent")
        val want = RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#B"))
        assertEntailed(graph, result, want)
    }

    /** Explicit OWL declarations (typical DL TBox/ABox style in Turtle). */
    @Test
    fun dlTransitiveSubClassChainEntailsInstanceTypes() {
        val turtle =
            """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A a owl:Class .
            ex:B a owl:Class .
            ex:C a owl:Class .

            ex:A rdfs:subClassOf ex:B .
            ex:B rdfs:subClassOf ex:C .

            ex:i a owl:NamedIndividual ;
                 rdf:type ex:A .
            """.trimIndent()

        val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
        val result = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph)

        assertTrue(result.consistencyCheck.isConsistent)
        assertEntailed(
            graph,
            result,
            RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#B")),
            RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#C")),
        )
    }

    /** Domain axioms are OWL DL; not covered by RDFS-only engines. */
    @Test
    fun dlObjectPropertyDomainEntailsSubjectType() {
        val turtle =
            """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:D a owl:Class .
            ex:p a owl:ObjectProperty ;
                 rdfs:domain ex:D .

            ex:i a owl:NamedIndividual .
            ex:j a owl:NamedIndividual .
            ex:i ex:p ex:j .
            """.trimIndent()

        val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
        val result = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph)

        assertTrue(result.consistencyCheck.isConsistent)
        assertEntailed(
            graph,
            result,
            RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#D")),
        )
    }

    /** Intersection introduction from an equivalence axiom. */
    @Test
    fun dlIntersectionEquivalentClassInfersFactorTypes() {
        val turtle =
            """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A a owl:Class .
            ex:B a owl:Class .
            ex:C a owl:Class ;
                 owl:equivalentClass [
                     a owl:Class ;
                     owl:intersectionOf ( ex:A ex:B )
                 ] .

            ex:i a owl:NamedIndividual ;
                 rdf:type ex:C .
            """.trimIndent()

        val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
        val result = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph)

        assertTrue(result.consistencyCheck.isConsistent)
        assertEntailed(
            graph,
            result,
            RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#A")),
            RdfTriple(Iri("http://example.org/ns#i"), rdfType, Iri("http://example.org/ns#B")),
        )
    }

    @Test
    fun dlDisjointClassesWithSharedInstanceIsInconsistent() {
        val turtle =
            """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A a owl:Class .
            ex:B a owl:Class ;
                 owl:disjointWith ex:A .

            ex:i a owl:NamedIndividual ;
                 rdf:type ex:A , ex:B .
            """.trimIndent()

        val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
        val result = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph)

        assertFalse(
            result.consistencyCheck.isConsistent,
            "HermiT should detect disjoint classes with a shared individual",
        )
        assertTrue(result.inferredTriples.isEmpty(), "no materialization for inconsistent ontology")
    }

    private fun assertEntailed(graph: RdfGraph, result: ReasoningResult, vararg expected: RdfTriple) {
        assertTrue(result.consistencyCheck.isConsistent)
        val materialized = graph.getTriples().toSet() + result.inferredTriples.toSet()
        for (t in expected) {
            assertTrue(
                t in materialized,
                "expected $t in asserted ∪ inferred; inferredOnly=${result.inferredTriples}",
            )
        }
    }
}
