package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The terms `onto-qa enrich` adds are the tool's own vocabulary (`oqsh:`): they are not undeclared external terms
 * (P34, P35), so an enriched ontology gets no finding for them, while an undeclared term of the ontology still does.
 */
class EnrichmentVocabularyExemptionTest {

    @Test
    fun `the enrichment vocabulary is exempt from P34 and P35`() {
        val graph = Rdf.parse(
            """
            @prefix ex: <http://example.org/o#> .
            @prefix ey: <http://example.org/other#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix oqsh: <http://example.org/owl-quality-shacl#> .
            <http://example.org/o> a owl:Ontology .
            ex:Car a owl:Class ; rdfs:label "Car" ; oqsh:semanticallyCloseTo ex:Auto ; oqsh:labelDefinitionDriftScore 0.1 ; ey:note "x" .
            ex:Auto a owl:Class ; rdfs:label "Auto" ; rdfs:subClassOf ey:Undeclared .
            <urn:onto-quality:enrichment:1> a oqsh:Enrichment ; oqsh:model "m" ; oqsh:modelHash "h" ; oqsh:threshold 0.85 ;
                oqsh:tokenizer "t" ; oqsh:timestamp "2026-10-26T00:00:00Z"^^xsd:dateTime ; oqsh:entitiesProcessed 2 ;
                oqsh:pairsAboveThreshold 1 ; oqsh:similaritySearchMode "exact" .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val findings = QualityChecker.builder(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE)))
            .addCatalog(BundledCatalogs.OWL_QUALITY)
            .build()
            .check(graph)
            .findings
        fun of(shape: String) = findings.filter { it.violation.shapeUri?.endsWith("#$shape") == true }
        assertEquals(listOf("http://example.org/other#Undeclared"), of("UntypedClassShape").map { (it.violation.focusNode as Iri).value })
        assertEquals(listOf("http://example.org/other#note"), of("UntypedPropertyShape").map { (it.violation.value as Iri).value })
    }
}
