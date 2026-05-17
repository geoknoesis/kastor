package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OopsCalibrationTest {

    private val checker by lazy {
        val validator = ShaclValidation.validator()
        QualityChecker.builder(validator)
            .addCatalog(BundledCatalogs.OWL_QUALITY)
            .build()
    }

    @ParameterizedTest(name = "pitfall {0} in {1}")
    @MethodSource("calibrationCases")
    fun `OWL quality catalog detects OOPS pitfall in OOPS reference ontology`(
        pitfall: String,
        resourcePath: String,
    ) {
        val url = javaClass.classLoader.getResource(resourcePath)
        assumeTrue(url != null) { "No OOPS fixture at classpath:$resourcePath (see ATTRIBUTION.md)." }

        val ontology =
            url!!.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
        val report = checker.check(ontology)

        val detected =
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.Oops -> p.number == pitfall
                    else -> false
                }
            }
        assertTrue(
            detected,
            "Expected pitfall $pitfall to be detected in $resourcePath.\n" +
                "Findings actually detected:\n" +
                report.findings.joinToString("\n") {
                    "  ${it.pitfall ?: "no-pitfall"}: ${it.violation.message}"
                },
        )
    }

    /** Calibrates additional STRUCTURAL pitfalls with OOPS RDF/XML-derived fixtures (`v0.1.x` extension). */
    @ParameterizedTest(name = "structural tier extended — pitfall {0}")
    @ValueSource(
        strings = [
            "P01",
            "P03",
            "P13",
            "P20",
            "P23",
            "P24",
            "P33",
            "P36",
            "P38",
            "P39",
            "P40",
            "P41",
        ],
    )
    fun `structural tier catches extended OOPS pitfall fixtures`(pitfall: String) {
        val resourcePath = "oops-corpus/$pitfall.ttl"
        val url = javaClass.classLoader.getResource(resourcePath)
        assumeTrue(url != null) { "No OOPS fixture at classpath:$resourcePath (see ATTRIBUTION.md)." }

        val ontology =
            url!!.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
        val report = checker.check(ontology)

        val detected =
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.Oops -> p.number == pitfall
                    else -> false
                }
            }
        assertTrue(
            detected,
            "Expected pitfall $pitfall to be detected in $resourcePath.\n" +
                report.findings.joinToString("\n") {
                    "  ${it.pitfall ?: "no-pitfall"}: ${it.violation.message}"
                },
        )
    }

    @Test
    fun `structural tier catches Kastor K01 import cycle`() {
        val resourcePath = "oops-corpus/K01.ttl"
        val url = javaClass.classLoader.getResource(resourcePath)
        assumeTrue(url != null)
        val ontology = url!!.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
        val report = checker.check(ontology)
        val detected =
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.KastorExtension -> p.code == "K01"
                    else -> false
                }
            }
        assertTrue(detected, "Expected Kastor K01 (import cycle). Findings: ${report.findings}")
    }

    @Test
    fun `OOPS pitfall registry exposes documentation shape metadata`() {
        val meta = BundledCatalogs.OOPS_PITFALL_REGISTRY.shapeMetadata
        assertTrue(meta.size >= 48, "expected ≥48 registry entries, got ${meta.size}")
        assertTrue(meta.values.any { it.pitfall == PitfallReference.KastorExtension("K07") })
        assertTrue(meta.values.any { it.pitfall == PitfallReference.Oops("P14") })
        assertTrue(meta.values.any { it.pitfall == PitfallReference.KastorExtension("K01") })
        assertTrue(meta.values.any { it.pitfall == PitfallReference.KastorExtension("K06") })
    }

    @Test
    fun `HermiT preflight surfaces Kastor K07 on inconsistent ontology`() {
        assumeTrue(OntoQualityReasoning.supports(OntoQualityReasoningProfile.HERMIT))
        val validator = ShaclValidation.validator()
        val qc = QualityChecker.default(validator)
        val resourcePath = "oops-corpus/K07.ttl"
        val url = javaClass.classLoader.getResource(resourcePath)
        assumeTrue(url != null)
        val ontology = url!!.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
        val report = qc.check(ontology, OntoQualityReasoningProfile.HERMIT)
        assertFalse(report.conforms, "Expected non-conformance due to OWL inconsistency")
        val k07 =
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.KastorExtension -> p.code == "K07"
                    else -> false
                }
            }
        assertTrue(k07, "Expected K07 finding; got: ${report.findings}")
        assertTrue(report.findings.any { it.tier == QualityTier.REASONING })
    }

    @Nested
    @DisabledIfEnvironmentVariable(named = "KASTOR_SKIP_EMBEDDING_TESTS", matches = "1")
    inner class SemanticTierAfterEnrichment {

        private val validator by lazy { ShaclValidation.validator() }

        @ParameterizedTest(name = "semantic tier detects OOPS pitfall {0}")
        @ValueSource(strings = ["P02", "P12", "P21", "P32"])
        fun `semantic tier catches OOPS pitfall after enrichment`(pitfall: String) {
            val resourcePath = "oops-corpus/$pitfall.ttl"
            val url = javaClass.classLoader.getResource(resourcePath)
            assumeTrue(url != null) { "No fixture at classpath:$resourcePath" }

            val ontology =
                url!!.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
            val enriched = SemanticEnricher.default().enrich(ontology)

            val checker =
                QualityChecker.builder(validator)
                    .addCatalog(BundledCatalogs.EMBEDDING_QUALITY)
                    .build()
            val report = checker.check(enriched)

            val detected =
                report.findings.any { f ->
                    when (val p = f.pitfall) {
                        is PitfallReference.Oops -> p.number == pitfall
                        else -> false
                    }
                }
            assertTrue(
                detected,
                "Expected pitfall $pitfall in semantic tier.\n" +
                    "Findings: ${report.findings.joinToString("\n") { it.violation.message }}",
            )
        }
    }

    companion object {
        @JvmStatic
        fun calibrationCases(): Stream<Arguments> =
            Stream.of(
                Arguments.of("P04", "oops-corpus/P04.ttl"),
                Arguments.of("P06", "oops-corpus/P06.ttl"),
                Arguments.of("P08", "oops-corpus/P08.ttl"),
                Arguments.of("P09", "oops-corpus/P09.ttl"),
                Arguments.of("P11", "oops-corpus/P11.ttl"),
                Arguments.of("P19", "oops-corpus/P19.ttl"),
                Arguments.of("P22", "oops-corpus/P22_M1.ttl"),
                Arguments.of("P22", "oops-corpus/P22_M2.ttl"),
                Arguments.of("P22", "oops-corpus/P22_M3.ttl"),
                Arguments.of("P22", "oops-corpus/P22_M4.ttl"),
                Arguments.of("P25", "oops-corpus/P25.ttl"),
                Arguments.of("P26", "oops-corpus/P26.ttl"),
                Arguments.of("P27", "oops-corpus/P27.ttl"),
                Arguments.of("P34", "oops-corpus/P34.ttl"),
                Arguments.of("P35", "oops-corpus/P35.ttl"),
            )
    }
}
