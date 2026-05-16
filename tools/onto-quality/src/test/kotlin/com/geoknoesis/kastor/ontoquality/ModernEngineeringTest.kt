package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ModernEngineeringTest {

    private val checker by lazy {
        val validator = ShaclValidation.validator()
        QualityChecker.builder(validator)
            .addCatalog(BundledCatalogs.MODERN_ENGINEERING)
            .build()
    }

    @ParameterizedTest(name = "modern engineering catalogue detects {0}")
    @ValueSource(
        strings = [
            "N03", "N04", "N06", "N07", "N09",
            "N16", "N17", "N20",
            "N23",
            "N26",
            "N32",
            "N34",
        ],
    )
    fun `modern engineering catalogue detects pitfall in fixture`(pitfall: String) {
        val ontology = loadTurtleResource("/fixtures/modern-engineering-fixture.ttl")
        val report = checker.check(ontology)

        val detected =
            report.findings.any { f ->
                when (val p = f.pitfall) {
                    is PitfallReference.OntoQuality -> p.number == pitfall
                    else -> false
                }
            }
        assertTrue(
            detected,
            "Expected $pitfall to fire on the fixture.\n" +
                "Actual findings:\n" +
                report.findings.joinToString("\n") {
                    "  ${it.pitfall ?: "no-pitfall"} (${it.category}): ${it.violation.message}"
                },
        )
    }

    private fun loadTurtleResource(path: String) =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(path.trimStart('/')),
        ) { "Missing test resource $path" }.use {
            Rdf.parseFromInputStream(it, RdfFormat.TURTLE)
        }
}
