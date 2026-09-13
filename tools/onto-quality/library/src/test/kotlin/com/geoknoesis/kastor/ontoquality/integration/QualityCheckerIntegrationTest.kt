package com.geoknoesis.kastor.ontoquality.integration

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualityCheckerIntegrationTest {

    @Test
    fun `quality checker without metrics provider returns unsorted findings`() {
        val validator = ShaclValidation.validator()
        val checker =
            QualityChecker.builder(validator)
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .build()
        val report = checker.check(loadZooOntology())
        assertNull(report.metricsContext)
    }

    @Test
    fun `quality checker with metrics provider returns sorted findings and context`() {
        val validator = ShaclValidation.validator()
        val checker =
            QualityChecker.builder(validator)
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .withMetricsProvider(KastorMetricsProvider())
                .build()
        val report = checker.check(loadZooOntology())
        assertNotNull(report.metricsContext)
        assertTrue(report.findings.isNotEmpty())
        val n = minOf(5, report.findings.size)
        assertEquals(n, report.topFindings(n).size)
    }

    @Test
    fun `metrics provider failure does not break SHACL validation`() {
        val failingProvider =
            object : MetricsProvider {
                override fun compute(ontology: RdfGraph): MetricsContext =
                    throw RuntimeException("simulated failure")
            }
        val validator = ShaclValidation.validator()
        val checker =
            QualityChecker.builder(validator)
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .withMetricsProvider(failingProvider)
                .build()
        val report = checker.check(loadZooOntology())
        assertNull(report.metricsContext)
        assertTrue(report.findings.isNotEmpty())
    }

    @Test
    fun `metrics are computed on the asserted graph when a reasoner materialises inferences`() {
        val ontology = loadZooOntology()
        val seen = mutableListOf<RdfGraph>()
        val recordingProvider =
            object : MetricsProvider {
                override fun compute(ontology: RdfGraph): MetricsContext {
                    seen += ontology
                    return MetricsContext(summary = "recorded", entityImportance = emptyMap())
                }
            }
        val checker =
            QualityChecker.builder(ShaclValidation.validator())
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .withMetricsProvider(recordingProvider)
                .build()
        checker.check(ontology, OntoQualityReasoningProfile.RDFS)
        assertEquals(1, seen.size)
        assertTrue(seen.single() === ontology, "metrics provider must receive the asserted graph")
        assertEquals(ontology.getTriples().size, seen.single().getTriples().size)
    }

    @Test
    fun `RDFS reasoning does not turn the asserted hierarchy into a cycle for metrics`() {
        val ttl =
            """
            @prefix : <http://example.org/r#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :A a owl:Class ; rdfs:label "A" .
            :B a owl:Class ; rdfs:label "B" ; rdfs:subClassOf :A .
            :C a owl:Class ; rdfs:label "C" ; rdfs:subClassOf :B .
            """.trimIndent()
        val ontology = Rdf.parse(ttl, "TURTLE")
        val checker =
            QualityChecker.builder(ShaclValidation.validator())
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .withMetricsProvider(KastorMetricsProvider())
                .build()
        val asserted = checker.check(ontology).metricsContext
        val reasoned = checker.check(ontology, OntoQualityReasoningProfile.RDFS).metricsContext
        assertNotNull(reasoned)
        assertEquals(asserted, reasoned)
        assertEquals("1 direct subclasses; 2 transitive descendants", reasoned.entityHints["http://example.org/r#A"])
    }

    private fun loadZooOntology() =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("test-ontologies/zoo-with-pitfalls.ttl"),
        ).use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
}
