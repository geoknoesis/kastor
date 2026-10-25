package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SHACL §2.1.4: a result takes the severity of the shape that produced it, and a nested `sh:property` shape is its
 * own shape (default `sh:Violation`). A node-level `sh:severity sh:Warning` therefore does not reach the property
 * shapes under it; each must declare the severity itself.
 */
class BundledShapeSeverityTest {

    private val sh = "http://www.w3.org/ns/shacl#"
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val severity = Iri("${sh}severity")
    private val property = Iri("${sh}property")
    private val violation = Iri("${sh}Violation")

    /** Predicates that do not, on their own, make a property shape produce results. */
    private val nonConstraint =
        setOf("path", "message", "severity", "name", "description", "order", "group", "deactivated")
            .map { Iri("$sh$it") }
            .toSet() + rdfType

    @Test
    fun `every constraint-bearing property shape under a non-violation node shape declares its own severity`() {
        val missing = mutableListOf<String>()
        for (catalog in BundledCatalogs.allWithOopsRegistry) {
            val g = catalog.loadShapesGraph()
            val nodeShapes = g.find(null, rdfType, Iri("${sh}NodeShape")).map { it.subject }.distinct()
            for (nodeShape in nodeShapes) {
                val declared = g.objectOf(nodeShape, severity) ?: violation
                if (declared == violation) continue
                for (ps in g.find(nodeShape, property, null).map { it.obj }) {
                    ps as RdfResource
                    val bearsConstraint = g.find(ps, null, null).any { it.predicate !in nonConstraint }
                    if (bearsConstraint && g.objectOf(ps, severity) == null) {
                        missing += "${catalog.id}: $nodeShape (${(declared as Iri).value.substringAfter('#')})"
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "property shapes that silently report sh:Violation:\n${missing.joinToString("\n")}")
    }

    @Test
    fun `warning-level label and range findings are reported as warnings`() {
        val checker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.OWL_QUALITY).build()
        val data =
            Rdf.parse(
                """
                @prefix ex: <http://example.org/onto#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:A a owl:Class ; rdfs:comment "a" .
                ex:B a owl:Class ; rdfs:comment "b" ; rdfs:label "B"@en , "Bee"@en .
                ex:p a owl:ObjectProperty ; rdfs:label "p" ; rdfs:domain ex:A .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val bySeverity =
            checker.check(data).findings.associate { it.violation.message to it.violation.severity }
        assertEquals(ViolationSeverity.WARNING, bySeverity["Class has no rdfs:label."], "findings: $bySeverity")
        assertEquals(ViolationSeverity.WARNING, bySeverity["Object property has no rdfs:range."], "findings: $bySeverity")
        assertEquals(
            ViolationSeverity.WARNING,
            bySeverity["Class has more than one rdfs:label with the same language tag."],
            "findings: $bySeverity",
        )
    }

    private val targets =
        listOf("targetClass", "targetNode", "targetSubjectsOf", "targetObjectsOf", "targetWhere", "target").map { Iri("$sh$it") }

    /**
     * SHACL allows at most one value of `sh:severity`, `sh:path` and `sh:deactivated` per shape. An engine reads one of
     * several values in index order, so a duplicate makes the result depend on the order of the triples (round 12:
     * the dct:creator finding was a Warning without a reasoner and Info with one).
     */
    @Test
    fun `no shape declares more than one severity, path or deactivated flag`() {
        val duplicates = mutableListOf<String>()
        for (catalog in BundledCatalogs.allWithOopsRegistry) {
            val g = catalog.loadShapesGraph()
            for (predicate in listOf("severity", "path", "deactivated").map { Iri("$sh$it") }) {
                g.find(null, predicate, null).groupBy { it.subject }.filterValues { it.size > 1 }.forEach { (subject, triples) ->
                    duplicates += "${catalog.id}: $subject has ${triples.size} ${predicate.value.substringAfter('#')} values"
                }
            }
        }
        assertTrue(duplicates.isEmpty(), duplicates.joinToString("\n"))
    }

    /** The severity of every detector is a decision, written down: no active targeted node shape relies on the default. */
    @Test
    fun `every active targeted node shape declares exactly one severity`() {
        val undeclared = mutableListOf<String>()
        for (catalog in BundledCatalogs.allWithOopsRegistry) {
            val g = catalog.loadShapesGraph()
            val targeted = targets.flatMap { t -> g.find(null, t, null).map { it.subject } }.distinct()
            for (shape in targeted) {
                val deactivated = g.find(shape, Iri("${sh}deactivated"), null).any { (it.obj as? com.geoknoesis.kastor.rdf.Literal)?.lexical == "true" }
                if (deactivated) continue
                val count = g.find(shape, severity, null).size
                if (count != 1) undeclared += "${catalog.id}: $shape declares $count sh:severity values"
            }
        }
        assertTrue(undeclared.isEmpty(), undeclared.joinToString("\n"))
    }

    /** Severities come from the shapes, not from the order a reasoner run happens to copy them in. */
    @Test
    fun `finding severities do not depend on the reasoner`() {
        val checker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.OWL_QUALITY).build()
        val data =
            Rdf.parse(
                """
                @prefix ex: <http://example.org/onto#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                <http://example.org/onto> a owl:Ontology ; rdfs:label "o" ; rdfs:comment "c" .
                ex:A a owl:Class ; rdfs:comment "a" .
                ex:p a owl:ObjectProperty ; rdfs:label "p" ; rdfs:domain ex:A .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )
        fun severities(profile: com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile) =
            checker.check(data, profile).findings.associate { "${it.violation.focusNode} ${it.violation.message}" to it.violation.severity }
        val asserted = severities(com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile.NONE)
        val reasoned = severities(com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile.OWL_RL)
        assertEquals(ViolationSeverity.WARNING, asserted["<http://example.org/onto> Ontology has no dct:creator (or equivalent attribution)."], "findings: $asserted")
        for ((key, value) in asserted) {
            reasoned[key]?.let { assertEquals(value, it, "severity of '$key' changed with the reasoner") }
        }
    }

    private fun RdfGraph.objectOf(subject: RdfResource, predicate: Iri) = find(subject, predicate, null).firstOrNull()?.obj
}
