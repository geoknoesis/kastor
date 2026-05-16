package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class QualityCheckerTest {

    @Test
    fun `OWL quality catalog catches planted pitfalls in zoo ontology`() {
        val validator = ShaclValidation.validator()
        val checker =
            QualityChecker.builder(validator)
                .addCatalog(BundledCatalogs.OWL_QUALITY)
                .build()

        val stream =
            checkNotNull(
                javaClass.classLoader.getResourceAsStream("test-ontologies/zoo-with-pitfalls.ttl"),
            ) {
                "Missing test ontology test-ontologies/zoo-with-pitfalls.ttl"
            }
        val ontology = stream.use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
        val report = checker.check(ontology)

        assertTrue(
            report.findings.any {
                it.pitfall == PitfallReference.Oops("P09")
            },
            "Expected OOPS! P09 finding",
        )
        assertTrue(
            report.findings.any {
                it.pitfall == PitfallReference.Oops("P06")
            },
            "Expected OOPS! P06 finding",
        )
        assertTrue(
            report.findings.any {
                it.pitfall == PitfallReference.Oops("P34")
            },
            "Expected OOPS! P34 finding",
        )

        println(report.describeMarkdown())
    }
}
