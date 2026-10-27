package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.embed.EnrichmentVocabulary
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * `pipeline` checks the graph its own enrichment produced: the terms of the tool's vocabulary (`oqsh:`) that the
 * enrichment adds are not undeclared external terms (P34, P35), so a clean ontology stays clean once enriched.
 */
class CliEnrichmentVocabularyTest {
    @TempDir lateinit var dir: Path

    private val clean =
        """
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix ex: <http://example.org/o#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix dct: <http://purl.org/dc/terms/> .
        <http://example.org/o> a owl:Ontology ; rdfs:label "O" ; rdfs:comment "An ontology" ; dct:creator "me" ; owl:versionInfo "1" ; dct:license <https://creativecommons.org/licenses/by/4.0/> .
        ex:Car a owl:Class ; rdfs:label "Car"@en ; rdfs:comment "A motor car."@en ; skos:example "Fiat" .
        ex:Automobile a owl:Class ; rdfs:label "Automobile"@en ; rdfs:comment "A motor vehicle."@en ; skos:example "Ford".
        ex:Vehicle a owl:Class ; rdfs:label "Vehicle"@en ; rdfs:comment "Something that transports."@en .
        ex:Car rdfs:subClassOf ex:Vehicle . ex:Automobile rdfs:subClassOf ex:Vehicle .
        """.trimIndent()

    /**
     * The output of `SemanticEnricher.enrich` for [clean], fixed: every term of [EnrichmentVocabulary] is used, as the
     * enricher uses it (a provenance node typed `oqsh:Enrichment`, a similarity link and a drift score per class).
     */
    private object FixedEnrichment : PipelineEnricherFactory {
        override fun open(options: EmbeddingCliOptions): PipelineEnricher =
            object : PipelineEnricher {
                override fun enrich(ontology: RdfGraph): RdfGraph {
                    val out = JenaBridge.createEmptyModel()
                    out.addTriples(ontology.getTriples())
                    val root = Iri("urn:onto-quality:enrichment:00000000-0000-0000-0000-000000000000")
                    val v = EnrichmentVocabulary
                    fun s(text: String) = Literal(text, XSD.string)
                    out.addTriples(
                        listOf(
                            RdfTriple(root, RDF.type, v.Enrichment),
                            RdfTriple(root, v.model, s("fixture-model")),
                            RdfTriple(root, v.modelHash, s("unknown")),
                            RdfTriple(root, v.threshold, Literal("0.85", XSD.decimal)),
                            RdfTriple(root, v.tokenizer, s("fixture-model")),
                            RdfTriple(root, v.similaritySearchMode, s("exact")),
                            RdfTriple(root, v.timestamp, Literal("2026-10-26T00:00:00Z", XSD.dateTime)),
                            RdfTriple(root, v.entitiesProcessed, Literal("3", XSD.integer)),
                            RdfTriple(root, v.pairsAboveThreshold, Literal("0", XSD.integer)),
                            RdfTriple(Iri("http://example.org/o#Car"), v.semanticallyCloseTo, Iri("http://example.org/o#Vehicle")),
                            RdfTriple(Iri("http://example.org/o#Car"), v.labelDefinitionDriftScore, Literal("0.100000", XSD.decimal)),
                        ),
                    )
                    return out
                }

                override fun close() = Unit
            }
    }

    @Test
    fun `an enriched clean ontology has no findings at warning level`() {
        val onto = dir.resolve("clean.ttl").also { it.writeText(clean) }.toString()
        val out = dir.resolve("report.json")
        val err = StringBuilder()
        val status = runOntoQa(
            listOf("pipeline", onto, "--severity", "warning", "--format", "json", "--output", out.toString()),
            CliEnvironment(enricherFactory = FixedEnrichment),
        ) { err.appendLine(it) }
        val findings = Json.parseToJsonElement(out.readText()).jsonObject.getValue("findings").jsonArray.map { it.jsonObject }
        val warnings = findings.filter { it.getValue("severity").jsonPrimitive.content.lowercase() != "info" }
        assertEquals(emptyList(), warnings.map { it.toString() }, err.toString())
        assertEquals(EXIT_OK, status, err.toString())
    }
}
