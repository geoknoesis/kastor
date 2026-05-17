package com.geoknoesis.kastor.ontoquality.integration

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
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

    private fun loadZooOntology() =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("test-ontologies/zoo-with-pitfalls.ttl"),
        ).use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
}
