package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Cycle detectors on a deep hierarchy. The rdfs:subClassOf cycle query used to pair every ancestor of a class with a
 * walk back from it, cubic in the depth: a 1,500-class chain did not finish in the 5-minute validation budget. It is
 * now a reachability test from each direct superclass. The budget here (2 minutes) is a backstop with a wide margin,
 * not a timing assertion: the chain checks in seconds.
 */
class DeepHierarchyTest {

    private fun chain(depth: Int, closeCycle: Boolean): String =
        buildString {
            appendLine("@prefix ex: <http://example.org/deep#> .")
            appendLine("@prefix owl: <http://www.w3.org/2002/07/owl#> .")
            appendLine("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
            for (i in 0 until depth) {
                append("ex:C$i a owl:Class ; rdfs:label \"c $i\" ; rdfs:comment \"c\" .")
                if (i > 0) append(" ex:C$i rdfs:subClassOf ex:C${i - 1} .")
                append(" ex:p$i a owl:ObjectProperty .")
                if (i > 0) append(" ex:p$i rdfs:subPropertyOf ex:p${i - 1} .")
                appendLine()
            }
            if (closeCycle) appendLine("ex:C0 rdfs:subClassOf ex:C${depth - 1} . ex:p0 rdfs:subPropertyOf ex:p${depth - 1} .")
        }

    private val checker =
        QualityChecker.builder(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE, timeout = Duration.ofMinutes(2))))
            .addCatalog(BundledCatalogs.OWL_QUALITY)
            .build()

    private fun cycleFindings(turtle: String, pitfallMessage: String): Set<String> =
        checker.check(Rdf.parse(turtle, RdfFormat.TURTLE)).findings
            .filter { pitfallMessage in it.violation.message }
            .mapNotNull { (it.violation.focusNode as? Iri)?.value }
            .toSet()

    @Test
    fun `a deep acyclic hierarchy is checked within the budget and has no cycle`() {
        val turtle = chain(depth = 1_500, closeCycle = false)
        assertEquals(emptySet<String>(), cycleFindings(turtle, "cycle"))
    }

    @Test
    fun `a long cycle reports every class and property on it`() {
        val turtle = chain(depth = 300, closeCycle = true)
        assertEquals(300, cycleFindings(turtle, "rdfs:subClassOf cycle").size)
        assertEquals(300, cycleFindings(turtle, "rdfs:subPropertyOf cycle").size)
    }
}
