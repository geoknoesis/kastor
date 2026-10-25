package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class LabelExtractorTest {

    private val prefixes =
        """
        @prefix ex: <http://example.org/v#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        """.trimIndent()

    private fun texts(turtle: String): Map<String, String> =
        LabelExtractor.extractLabelTexts(Rdf.parse(prefixes + "\n" + turtle, RdfFormat.TURTLE))
            .mapKeys { (it.key as Iri).value.substringAfter('#') }

    @Test
    fun `a multilingual concept is embedded in the dominant language, preferred labels first`() {
        val found =
            texts(
                """
                ex:car skos:prefLabel "car"@en , "voiture"@fr , "Auto"@de ; skos:altLabel "automobile"@en , "bagnole"@fr .
                ex:bike skos:prefLabel "bicycle"@en , "vélo"@fr .
                ex:train skos:prefLabel "train"@fr .
                ex:tram skos:altLabel "streetcar"@en , "tramway"@en .
                ex:bus rdfs:label "bus" ; skos:prefLabel "Bus"@de .
                """.trimIndent(),
            )
        assertEquals(
            mapOf(
                "car" to "car",
                "bike" to "bicycle",
                // No English label: the only language it has.
                "train" to "train",
                // No preferred label: the alternative labels, sorted.
                "tram" to "streetcar ; tramway",
                // No English label: untagged before another language.
                "bus" to "bus",
            ),
            found,
        )
    }

    @Test
    fun `the text does not depend on serialisation order`() {
        val a = texts("""ex:c rdfs:label "zeta"@en , "alpha"@en ; skos:prefLabel "alpha"@en .""")
        val b = texts("""ex:c skos:prefLabel "alpha"@en ; rdfs:label "alpha"@en , "zeta"@en .""")
        assertEquals(mapOf("c" to "alpha ; zeta"), a)
        assertEquals(a, b)
    }

    @Test
    fun `the dominant language is whatever most labels carry`() {
        val found =
            texts(
                """
                ex:a skos:prefLabel "chat"@fr , "cat"@en .
                ex:b skos:prefLabel "chien"@fr .
                ex:c skos:prefLabel "oiseau"@fr , "bird"@en .
                """.trimIndent(),
            )
        assertEquals(mapOf("a" to "chat", "b" to "chien", "c" to "oiseau"), found)
    }

    @Test
    fun `a requested language overrides the dominant one`() {
        val graph =
            Rdf.parse(
                prefixes + "\n" +
                    """
                    ex:car skos:prefLabel "car"@en , "voiture"@fr .
                    ex:bike skos:prefLabel "bicycle"@en , "vélo"@FR .
                    ex:bus skos:prefLabel "bus"@en .
                    ex:Tram a owl:Class ; rdfs:label "tram"@en , "tramway"@fr ; rdfs:comment "A tram."@en , "Un tramway."@fr .
                    """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val found = LabelExtractor.extractLabelTexts(graph, language = "FR").mapKeys { (it.key as Iri).value.substringAfter('#') }
        // English is dominant, French is requested; ex:bus has no French label and keeps its only language.
        assertEquals(mapOf("car" to "voiture", "bike" to "vélo", "bus" to "bus", "Tram" to "tramway"), found)
        val drift = LabelExtractor.extractLabelAndDefinitionTexts(graph, language = "fr").single()
        assertEquals("tramway" to "Un tramway.", drift.second)
        kotlin.test.assertFailsWith<IllegalArgumentException> { LabelExtractor.extractLabelTexts(graph, language = "not a tag") }
    }

    @Test
    fun `the definition follows the language of the label`() {
        val graph =
            Rdf.parse(
                prefixes + "\n" +
                    """
                    ex:Car a owl:Class ; rdfs:label "car"@en , "voiture"@fr ;
                        skos:definition "Un véhicule."@fr , "A road vehicle."@en .
                    ex:Velo a owl:Class ; rdfs:label "vélo"@fr ; rdfs:comment "Un deux-roues."@fr , "A bicycle."@en .
                    ex:Bare a owl:Class ; rdfs:label "bare"@en .
                    """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val pairs = LabelExtractor.extractLabelAndDefinitionTexts(graph).associate { (iri, p) -> iri.value.substringAfter('#') to p }
        assertEquals(mapOf("Car" to ("car" to "A road vehicle."), "Velo" to ("vélo" to "Un deux-roues.")), pairs)
    }

    @Test
    fun `the enricher embeds the requested label language`() {
        val graph =
            Rdf.parse(
                prefixes + "\n" + "ex:car skos:prefLabel \"car\"@en , \"voiture\"@fr . ex:bus skos:prefLabel \"bus\"@en .",
                RdfFormat.TURTLE,
            )
        val seen = mutableListOf<String>()
        val model = PrecomputedEmbeddingModel("fake", 2) { text -> seen += text; floatArrayOf(1f, text.length.toFloat()) }
        SemanticEnricher(model, 0.85, SimilarityLimitsPolicy.scaled(), SimilaritySearchMode.Exact, "fr").enrichmentOnly(graph)
        assertEquals(listOf("bus", "voiture"), seen.sorted())
        seen.clear()
        SemanticEnricher(model, 0.85, SimilarityLimitsPolicy.scaled(), SimilaritySearchMode.Exact, null).enrichmentOnly(graph)
        assertEquals(listOf("bus", "car"), seen.sorted())
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SemanticEnricher(model, 0.85, SimilarityLimitsPolicy.scaled(), SimilaritySearchMode.Exact, "not a tag")
        }
    }
}
