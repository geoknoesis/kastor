package com.geoknoesis.kastor.ontoquality.reasoning

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.catalog.EvaluationScopes
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Switching a reasoner on must not make structural detectors fire on entailments (a reflexive
 * `C rdfs:subClassOf C`, axiomatic triples on built-in vocabulary) nor make them go silent.
 */
class ReasonerCatalogueRegressionTest {

    private val profiles = OntoQualityReasoningProfile.values().filter { it != OntoQualityReasoningProfile.NONE }

    /**
     * Findings that a reasoner is allowed to add on the clean ontology, as `profile|shape|focus`. Empty: every
     * bundled detector that evaluates the entailed graph is silent on this ontology with every profile.
     */
    private val expectedOnlyWithReasoner: Set<String> = emptySet()

    private fun clean(): RdfGraph {
        val stream =
            checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/clean-small-ontology.ttl")) {
                "Missing fixture fixtures/clean-small-ontology.ttl"
            }
        return stream.use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
    }

    private fun shapeOf(f: QualityFinding): String =
        f.violation.shapeUri?.takeUnless { it.startsWith("_:") } ?: f.pitfall.toString()

    private fun key(f: QualityFinding): String = "${shapeOf(f)}|${f.violation.focusNode}|${f.stableMessage}"

    @Test
    fun `no bundled detector fires only because a reasoner is on`() {
        val checker = QualityChecker.default(ShaclValidation.validator())
        val ontology = clean()
        val baseline = checker.check(ontology).findings.map(::key).toSet()
        val unexpected = ArrayList<String>()
        var ran = 0
        for (profile in profiles) {
            if (!OntoQualityReasoning.supports(profile)) continue
            ran++
            for (f in checker.check(ontology, profile).findings) {
                val k = key(f)
                if (k in baseline) continue
                val listed = "$profile|${shapeOf(f)}|${f.violation.focusNode}"
                if (listed !in expectedOnlyWithReasoner) unexpected.add("$profile: $k")
            }
        }
        assertTrue(ran >= 3, "expected the Jena profiles to be available, ran $ran")
        assertEquals(emptyList<String>(), unexpected, "findings that appear only with a reasoner")
    }

    @Test
    fun `structural detectors report the same findings with every reasoner`() {
        val checker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.OWL_QUALITY).build()
        val ontology =
            Rdf.parse(
                """
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl:  <http://www.w3.org/2002/07/owl#> .
                @prefix ex:   <http://example.org/ns#> .
                <http://example.org/ns> a owl:Ontology ; rdfs:label "t" ; rdfs:comment "t" .
                ex:Orphan a owl:Class ; rdfs:label "Orphan" ; rdfs:comment "No links at all." .
                ex:A a owl:Class ; rdfs:label "A" ; rdfs:comment "a" ; rdfs:subClassOf ex:B .
                ex:B a owl:Class ; rdfs:label "B" ; rdfs:comment "b" ; rdfs:subClassOf ex:A .
                ex:Top a owl:Class ; rdfs:label "Top" ; rdfs:comment "top" .
                ex:Leaf a owl:Class ; rdfs:label "Leaf" ; rdfs:comment "leaf" ; rdfs:subClassOf ex:Top .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )

        fun foci(findings: List<QualityFinding>, shape: String): Set<String> =
            findings.filter { it.violation.shapeUri == "http://example.org/owl-quality-shacl#$shape" }
                .map { it.violation.focusNode.toString() }.toSet()

        val base = checker.check(ontology).findings
        val cycle = foci(base, "NoSubClassCycleShape")
        val orphan = foci(base, "OrphanClassShape")
        assertEquals(2, cycle.size, "A and B are in a cycle: $cycle")
        assertEquals(1, orphan.size, "Orphan is unconnected: $orphan")
        for (profile in profiles) {
            if (!OntoQualityReasoning.supports(profile)) continue
            val f = checker.check(ontology, profile).findings
            assertEquals(cycle, foci(f, "NoSubClassCycleShape"), "P06 with $profile")
            assertEquals(orphan, foci(f, "OrphanClassShape"), "P04 with $profile")
        }
    }

    @Test
    fun `detectors meant to see entailments still evaluate the materialised graph`() {
        val checker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.SKOS_VALIDATION).build()
        val ontology =
            Rdf.parse(
                """
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                @prefix ex:   <http://example.org/ns#> .
                ex:groups rdfs:domain skos:Concept .
                ex:scheme a skos:ConceptScheme ; ex:groups ex:other .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val s9 = "http://example.org/skos-validation-shacl#SchemeNotConceptShape"
        assertTrue(checker.check(ontology).findings.none { it.violation.shapeUri == s9 }, "S9 needs the entailed type")
        val withRdfs = checker.check(ontology, OntoQualityReasoningProfile.RDFS).findings
        assertTrue(withRdfs.any { it.violation.shapeUri == s9 }, "S9 sees ex:scheme a skos:Concept entailed by rdfs:domain")
    }

    @Test
    fun `a custom shape without the annotation evaluates the materialised graph`() {
        val shapes =
            Rdf.parse(
                """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <http://example.org/ns#> .
                ex:AnimalShape a sh:NodeShape ; sh:targetClass ex:Animal ;
                    sh:property [ sh:path ex:name ; sh:minCount 1 ] .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val scopes = EvaluationScopes.of(shapes)
        assertEquals(0, scopes.asserted.size)
        assertEquals(1, scopes.entailed.size)
        assertEquals(1, scopes.unclassified.size)
    }

    @Test
    fun `every active bundled shape declares the graph it evaluates`() {
        val missing = ArrayList<String>()
        for (catalog in BundledCatalogs.allWithOopsRegistry + BundledCatalogs.SHACL_QC_DESIGN) {
            val scopes = EvaluationScopes.of(catalog.loadShapesGraph())
            missing.addAll(scopes.unclassified.map { "${catalog.id}: $it" })
        }
        assertEquals(emptyList<String>(), missing, "active shapes without oqsh:evaluatedOn")
    }
}
