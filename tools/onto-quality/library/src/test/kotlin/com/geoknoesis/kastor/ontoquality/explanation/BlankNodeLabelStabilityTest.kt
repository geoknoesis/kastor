package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Only blank-node references (`_:label`) are replaced in messages; path and message blank nodes are keyed too. */
class BlankNodeLabelStabilityTest {
    private fun e(local: String): Iri = Iri("http://ex.org/onto#$local")

    private fun violation(focus: RdfTerm, message: String, path: List<RdfTerm>? = null, value: RdfTerm? = null): ValidationViolation =
        ValidationViolation(
            severity = ViolationSeverity.WARNING,
            constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
            focusNode = focus,
            message = message,
            path = path,
            value = value,
        )

    private fun finding(label: String, message: String): QualityFinding =
        QualityFinding(
            violation(BlankNode(label), message),
            QualityCategory.UNCATEGORIZED,
            null,
            QualityTier.STRUCTURAL,
            mapOf(BlankNode(label) to "_:k0123"),
        )

    private fun report(vararg violations: ValidationViolation): ValidationReport =
        ValidationReport(
            isValid = false,
            violations = violations.toList(),
            warnings = emptyList(),
            statistics =
                ValidationStatistics(
                    totalResources = 1,
                    validatedResources = 1,
                    totalConstraints = 1,
                    validatedConstraints = 1,
                    shapesProcessed = 1,
                    constraintsByType = emptyMap(),
                    violationsByType = emptyMap(),
                    warningsByType = emptyMap(),
                    averageValidationTimePerResource = Duration.ZERO,
                ),
            validationTime = Duration.ZERO,
            validatedResources = 1,
            validatedConstraints = 1,
        )

    @Test
    fun `a short label is not replaced inside an IRI or a word`() {
        val message = "Class http://ex.org/onto#b1 (<http://ex.org/b1>, 'b1', web1, b1) has an anonymous superclass _:b1."
        assertEquals(
            "Class http://ex.org/onto#b1 (<http://ex.org/b1>, 'b1', web1, b1) has an anonymous superclass _:k0123.",
            finding("b1", message).stableMessage,
        )
    }

    @Test
    fun `a one-letter label is replaced only as a blank-node reference`() {
        val message = "a restriction of http://ex.org/a#a and <urn:a> lacks a filler: _:a, (_:a) and _:ab; see http://ex.org/x/_:a"
        assertEquals(
            "a restriction of http://ex.org/a#a and <urn:a> lacks a filler: _:k0123, (_:k0123) and _:ab; see http://ex.org/x/_:a",
            finding("a", message).stableMessage,
        )
    }

    private fun data(focus: String, other: String): RdfGraph =
        MemoryGraph(
            listOf(
                RdfTriple(e("A"), e("p"), BlankNode(focus)),
                RdfTriple(BlankNode(focus), e("v"), e("one")),
                RdfTriple(e("B"), e("p"), BlankNode(other)),
                RdfTriple(BlankNode(other), e("v"), e("two")),
            ),
        )

    @Test
    fun `blank nodes that only the message mentions are keyed too`() {
        fun finding(focus: String, other: String): QualityFinding {
            val raw = report(violation(BlankNode(focus), "Node _:$focus clashes with _:$other (and with _:unknown7)"))
            return QualityReport.from(raw, emptyList(), null, data(focus, other)).findings.single()
        }
        val first = finding("b1", "b2")
        val second = finding("genid7", "genid3")
        assertFalse(first.stableMessage.contains("_:b1") || first.stableMessage.contains("_:b2"), first.stableMessage)
        assertTrue(first.stableMessage.contains("_:unknown7"), "a label that is no blank node of the graph is left alone")
        assertEquals(first.stableMessage, second.stableMessage)
        assertEquals(FindingRef.from(first), FindingRef.from(second))
        assertEquals(2, Regex("_:k[0-9a-f]{32}").findAll(first.stableMessage).map { it.value }.toSet().size, first.stableMessage)
    }

    private fun shapes(path: String, predicate: String): RdfGraph =
        MemoryGraph(
            listOf(
                RdfTriple(e("Shape"), Iri("http://www.w3.org/ns/shacl#property"), BlankNode("ps$path")),
                RdfTriple(BlankNode("ps$path"), Iri("http://www.w3.org/ns/shacl#path"), BlankNode(path)),
                RdfTriple(BlankNode(path), Iri("http://www.w3.org/ns/shacl#inversePath"), e(predicate)),
                RdfTriple(BlankNode(path), RDF.type, e("Path")),
            ),
        )

    @Test
    fun `blank nodes of the shapes graph in a path are keyed by their structure, not their label`() {
        fun ref(label: String, predicate: String = "q"): FindingRef {
            val raw = report(violation(e("A"), "bad path value", path = listOf(BlankNode(label))))
            val keys = QualityReport.blankNodeKeys(raw, data("x1", "x2"), null) { shapes(label, predicate) }
            return FindingRef.from(QualityReport.fromKeyed(raw, emptyList(), null, keys).findings.single())
        }
        assertEquals(ref("p1"), ref("genid42"), "the ref depends on the label of the path node")
        assertNotEquals(ref("p1"), ref("p1", predicate = "r"), "paths with another structure share a ref")
    }

    @Test
    fun `a path blank node without a key never contributes its label to the ref`() {
        fun ref(label: String): FindingRef =
            FindingRef.from(
                QualityFinding(violation(e("A"), "bad path value", path = listOf(BlankNode(label))), QualityCategory.UNCATEGORIZED, null, QualityTier.STRUCTURAL),
            )
        assertEquals(ref("p1"), ref("genid42"))
    }
}
