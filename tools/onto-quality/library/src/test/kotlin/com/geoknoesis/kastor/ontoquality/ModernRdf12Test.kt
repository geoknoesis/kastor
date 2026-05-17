package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * RDF 1.2 catalogue checks. **[N28] [N29]** fire on `rdf12-fixture.ttl`.
 *
 * **[N30]** (`NoTripleTermInSubjectShape`) is not exercised here: RDF 1.2 disallows triple terms
 * in subject position, so Turtle that would violate it is rejected at parse time before SHACL runs.
 */
class ModernRdf12Test {

    private val checker by lazy {
        QualityChecker.builder(ShaclValidation.validator())
            .addCatalog(BundledCatalogs.RDF12_QUALITY)
            .build()
    }

    @ParameterizedTest(name = "RDF 1.2 catalogue detects {0}")
    @ValueSource(strings = ["N28", "N29"])
    fun `rdf12 catalogue detects pitfall in fixture`(pitfall: String) {
        val ontology = loadFixture()
        val report = checker.check(ontology)
        assertTrue(
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.OntoQuality -> p.number == pitfall
                    else -> false
                }
            },
            "Expected $pitfall among findings:\n${report.findings}",
        )
    }

    @Test
    fun `N30 shape is bundled for engines that expose triple-term subjects`() {
        val ttl =
            checkNotNull(
                javaClass.classLoader.getResourceAsStream("shapes/rdf12-quality-shacl.ttl"),
            ).use { String(it.readAllBytes(), Charsets.UTF_8) }
        assertTrue(
            ttl.contains("NoTripleTermInSubjectShape"),
            "Catalogue should retain the defensive N30 triple-term-subject constraint",
        )
    }

    private fun loadFixture() =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("fixtures/rdf12-fixture.ttl"),
        ) { "Missing fixtures/rdf12-fixture.ttl" }.use {
            Rdf.parseFromInputStream(it, RdfFormat.TURTLE)
        }
}
