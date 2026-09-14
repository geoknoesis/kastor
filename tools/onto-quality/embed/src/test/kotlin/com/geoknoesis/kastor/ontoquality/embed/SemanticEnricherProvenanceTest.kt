package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticEnricherProvenanceTest {
    private val ontology =
        Rdf.parse(
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://example.org/prov#> .
            ex:Car rdfs:label "car" .
            ex:Automobile rdfs:label "automobile" .
            ex:Banana rdfs:label "banana" .
            """.trimIndent(),
            "TURTLE",
        )

    /** "car" and "automobile" get the same direction; "banana" is orthogonal. */
    private val model =
        PrecomputedEmbeddingModel(name = "fixture-model", dimension = 2) { text ->
            if (text.contains("banana")) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
        }

    private fun modeOf(mode: SimilaritySearchMode): List<Literal> {
        val enriched = SemanticEnricher(model, 0.9, SimilarityLimitsPolicy.scaled(), mode).enrich(ontology)
        val triples = enriched.getTriples()
        val roots = triples.filter { it.predicate == RDF.type && it.obj == EnrichmentVocabulary.Enrichment }.map { it.subject }
        assertEquals(1, roots.size, triples.toString())
        val closeTo =
            RdfTriple(Iri("http://example.org/prov#Automobile"), EnrichmentVocabulary.semanticallyCloseTo, Iri("http://example.org/prov#Car"))
        assertTrue(closeTo in triples, triples.toString())
        return triples.filter { it.subject == roots.single() && it.predicate == EnrichmentVocabulary.similaritySearchMode }.map { it.obj as Literal }
    }

    @Test
    fun `enrichment provenance records the similarity search mode`() {
        assertEquals(listOf(Literal("exact", XSD.string)), modeOf(SimilaritySearchMode.Exact))
        assertEquals(
            listOf(Literal("approximate-lsh(tables=20,bitsPerTable=10,seed=42)", XSD.string)),
            modeOf(SimilaritySearchMode.ApproximateLsh()),
        )
    }
}
