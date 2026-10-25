package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * False-positive regression corpus (`test-ontologies/fp-corpus`): valid, well-documented ontologies that exercise
 * the constructs detectors tend to misread (blank-node class expressions, OWL 2 axioms and datatypes, imports and
 * reused vocabularies, punning, SKOS stated in both directions, multilingual labels and non-ASCII names, a large
 * ontology). Every bundled catalogue runs on them, without and with a reasoner; none may report a warning or a
 * violation. Informational findings (suggestions such as "declare an inverse") are allowed.
 */
class FalsePositiveCorpusTest {

    private val checker by lazy {
        QualityChecker.builder(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE))).withAllBundledCatalogs().build()
    }

    private fun load(name: String): RdfGraph =
        checkNotNull(javaClass.classLoader.getResourceAsStream("test-ontologies/fp-corpus/$name")) { "missing corpus file $name" }
            .use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE, "https://w3id.org/kastor/fp/$name") }

    private fun blocking(report: QualityReport): List<String> =
        report.findings
            .filter { it.violation.severity != ViolationSeverity.INFO && it.violation.severity != ViolationSeverity.DEBUG && it.violation.severity != ViolationSeverity.TRACE }
            .map { "${it.violation.severity} ${it.violation.shapeUri}: ${it.stableMessage} [focus ${FindingTerms.focus(it)}]" }
            .sorted()

    @ParameterizedTest(name = "{0} with reasoner {1}")
    @MethodSource("corpus")
    fun `valid ontologies produce no warning or violation`(file: String, reasoning: OntoQualityReasoningProfile) {
        val report = checker.check(load(file), reasoning)
        assertFalse(report.truncated)
        assertEquals(emptyList<String>(), blocking(report), "false positives in $file")
    }

    @Test
    fun `a large documented ontology produces no warning or violation`() {
        val turtle = buildString {
            appendLine("@prefix ex: <https://w3id.org/kastor/fp/large#> .")
            appendLine("@prefix owl: <http://www.w3.org/2002/07/owl#> .")
            appendLine("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
            appendLine("@prefix dct: <http://purl.org/dc/terms/> .")
            appendLine("@prefix dcat: <http://www.w3.org/ns/dcat#> .")
            appendLine("@prefix skos: <http://www.w3.org/2004/02/skos/core#> .")
            appendLine("<https://w3id.org/kastor/fp/large> a owl:Ontology ; rdfs:label \"Large\"@en ; rdfs:comment \"Many classes.\"@en ;")
            appendLine("  dct:creator <https://orcid.org/0000-0002-1825-0097> ; dct:license <https://creativecommons.org/licenses/by/4.0/> ;")
            appendLine("  dcat:contactPoint <mailto:large@example.org> ; owl:versionInfo \"1\" .")
            appendLine("ex:Thing0 a owl:Class ; rdfs:label \"thing 0\"@en ; rdfs:comment \"The root.\"@en ; skos:example \"x\"@en .")
            for (i in 1 until 3_000) {
                appendLine("ex:Thing$i a owl:Class ; rdfs:label \"thing $i\"@en ; rdfs:comment \"Thing number $i.\"@en ; rdfs:subClassOf ex:Thing${i / 2} .")
            }
        }
        val report = checker.check(Rdf.parse(turtle, RdfFormat.TURTLE))
        assertFalse(report.truncated)
        assertEquals(emptyList<String>(), blocking(report))
    }

    companion object {
        private val files = listOf("owl2-blank-nodes.ttl", "imports-external.ttl", "punning.ttl", "skos-both-directions.ttl", "owl2-multilingual.ttl")

        @JvmStatic
        fun corpus(): List<Arguments> =
            files.flatMap { f -> listOf(OntoQualityReasoningProfile.NONE, OntoQualityReasoningProfile.OWL_RL).map { Arguments.of(f, it) } }
    }
}
