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
                ex:p a owl:ObjectProperty ; rdfs:label "p" ; rdfs:domain ex:A .
                """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val bySeverity =
            checker.check(data).findings.associate { it.violation.message to it.violation.severity }
        assertEquals(ViolationSeverity.WARNING, bySeverity["Class has no rdfs:label."], "findings: $bySeverity")
        assertEquals(ViolationSeverity.WARNING, bySeverity["Object property has no rdfs:range."], "findings: $bySeverity")
    }

    private fun RdfGraph.objectOf(subject: RdfResource, predicate: Iri) = find(subject, predicate, null).firstOrNull()?.obj
}
