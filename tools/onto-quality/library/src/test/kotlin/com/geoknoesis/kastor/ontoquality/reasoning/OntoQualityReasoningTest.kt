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
        // OWL_RL binds to Jena's ReasonerType.OWL_RL rule reasoner, OWL_MICRO to Jena's OWL Micro reasoner.
        org.junit.jupiter.api.Assertions.assertEquals(
            com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_RL,
            OntoQualityReasoningProfile.OWL_RL.toReasonerConfigOrNull()?.reasonerType,
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_MICRO,
            OntoQualityReasoningProfile.OWL_MICRO.toReasonerConfigOrNull()?.reasonerType,
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

    @Test
    fun owlMicroUsesJenaOwlMicroReasoner() {
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

        assertTrue(OntoQualityReasoning.supports(OntoQualityReasoningProfile.OWL_MICRO))
        val base = Rdf.parse(turtle, RdfFormat.TURTLE)
        val expanded = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.OWL_MICRO)

        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val ns = "http://example.org/ns#"
        val triples = expanded.getTriples()
        assertTrue(triples.containsAll(base.getTriples()), "Asserted triples must be kept")
        assertTrue(RdfTriple(Iri(ns + "i"), type, Iri(ns + "B")) in triples, "Expected ex:i a ex:B; triples: $triples")
        assertTrue(RdfTriple(Iri(ns + "j"), Iri(ns + "q"), Iri(ns + "i")) in triples, "Expected ex:j ex:q ex:i; triples: $triples")
    }

    /**
     * Jena's OWL Micro rule set (`etc/owl-fb-micro.rules`) states "no equality reasoning (sameAs, FunctionalProperty
     * ...)": it has no `fp1` rule. The full OWL rule set behind [OntoQualityReasoningProfile.OWL_RL]
     * (`etc/owl-fb.rules`, `ReasonerRegistry.getOWLReasoner()`) derives `?B owl:sameAs ?C` from a functional property
     * with two values. The same input therefore distinguishes the two profiles, proving OWL_MICRO is not an alias of
     * OWL_RL.
     */
    @Test
    fun owlRlDerivesFunctionalPropertyEqualityButOwlMicroDoesNot() {
        val turtle =
            """
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix ex:   <http://example.org/ns#> .

            ex:hasMother a owl:ObjectProperty , owl:FunctionalProperty .
            ex:alice ex:hasMother ex:carol , ex:caroline .
            """.trimIndent()
        val base = Rdf.parse(turtle, RdfFormat.TURTLE)
        val ns = "http://example.org/ns#"
        val sameAs = RdfTriple(Iri(ns + "carol"), Iri("http://www.w3.org/2002/07/owl#sameAs"), Iri(ns + "caroline"))

        val rl = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.OWL_RL).getTriples()
        val micro = OntoQualityReasoning.expand(base, OntoQualityReasoningProfile.OWL_MICRO).getTriples()

        org.junit.jupiter.api.Assertions.assertEquals(true, sameAs in rl, "OWL_RL must derive ex:carol owl:sameAs ex:caroline")
        org.junit.jupiter.api.Assertions.assertEquals(false, sameAs in micro, "OWL_MICRO has no equality rules; triples: $micro")
    }
}
