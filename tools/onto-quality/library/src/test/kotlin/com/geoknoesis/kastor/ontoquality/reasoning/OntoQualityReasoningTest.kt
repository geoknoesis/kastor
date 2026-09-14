package com.geoknoesis.kastor.ontoquality.reasoning

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.Iri
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OntoQualityReasoningTest {

    @Test
    fun rdfsExpandsInstanceTypeAlongSubClass() {
        val turtle =
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A rdfs:subClassOf ex:B .
            ex:i rdf:type ex:A .
            """.trimIndent()

        val base = Rdf.parse(turtle, RdfFormat.TURTLE)
        val expanded = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.RDFS)

        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val i = Iri("http://example.org/ns#i")
        val b = Iri("http://example.org/ns#B")
        val inferred = RdfTriple(i, type, b)
        assertTrue(
            expanded.getTriples().contains(inferred),
            "Expected Jena RDFS to entail ex:i a ex:B; triples: ${expanded.getTriples()}",
        )
    }

    @Test
    fun noneLeavesGraphUnchanged() {
        val turtle =
            """
            @prefix ex: <http://example.org/ns#> .
            ex:a ex:p ex:o .
            """.trimIndent()
        val g = Rdf.parse(turtle, RdfFormat.TURTLE)
        val out = OntoQualityReasoning.expand(g, OntoQualityReasoningProfile.NONE)
        assertTrue(out.getTriples().size == g.getTriples().size)
    }

    @Test
    fun owlMicroMaterialisesSmallOntologyWithoutThrowing() {
        val turtle =
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A a owl:Class ; rdfs:subClassOf ex:B .
            ex:B a owl:Class .
            ex:p a owl:ObjectProperty ; owl:inverseOf ex:q .
            ex:i rdf:type ex:A ; ex:p ex:j .
            """.trimIndent()

        val base = Rdf.parse(turtle, RdfFormat.TURTLE)
        assertTrue(OntoQualityReasoning.supports(OntoQualityReasoningProfile.OWL_RL))
        // OWL_RL binds to Jena's ReasonerType.OWL_RL rule reasoner; the deprecated OWL_MICRO name is an alias for it.
        org.junit.jupiter.api.Assertions.assertEquals(
            com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_RL,
            OntoQualityReasoningProfile.OWL_RL.toReasonerConfigOrNull()?.reasonerType,
        )
        @Suppress("DEPRECATION")
        val alias = OntoQualityReasoningProfile.OWL_MICRO
        org.junit.jupiter.api.Assertions.assertEquals(
            com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_RL,
            alias.toReasonerConfigOrNull()?.reasonerType,
        )
        val expanded = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.OWL_RL)

        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val ns = "http://example.org/ns#"
        val triples = expanded.getTriples()
        assertTrue(triples.containsAll(base.getTriples()), "Asserted triples must be kept")
        assertTrue(
            RdfTriple(Iri(ns + "i"), type, Iri(ns + "B")) in triples,
            "Expected OWL_RL to entail ex:i a ex:B; triples: $triples",
        )
        assertTrue(
            RdfTriple(Iri(ns + "j"), Iri(ns + "q"), Iri(ns + "i")) in triples,
            "Expected OWL_RL to entail ex:j ex:q ex:i via owl:inverseOf; triples: $triples",
        )
    }

    @Test
    fun hermitExpandsInstanceTypeAlongSubClass() {
        val turtle =
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:A rdfs:subClassOf ex:B .
            ex:i rdf:type ex:A .
            """.trimIndent()

        val base = Rdf.parse(turtle, RdfFormat.TURTLE)
        val expanded = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.HERMIT)

        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val i = Iri("http://example.org/ns#i")
        val b = Iri("http://example.org/ns#B")
        val inferred = RdfTriple(i, type, b)
        assertTrue(
            expanded.getTriples().contains(inferred),
            "Expected HermiT to entail ex:i a ex:B; triples: ${expanded.getTriples()}",
        )
    }
}
