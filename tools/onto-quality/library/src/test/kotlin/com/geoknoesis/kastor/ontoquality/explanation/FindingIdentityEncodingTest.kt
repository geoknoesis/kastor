package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.*

class FindingIdentityEncodingTest {
    private fun finding(value: RdfTerm? = null) = QualityFinding(
        ValidationViolation(ViolationSeverity.WARNING, ShaclConstraint(ConstraintType.NODE_KIND),
            Iri("urn:focus"), "message", value = value),
        QualityCategory.UNCATEGORIZED, null, QualityTier.STRUCTURAL,
    )
    private fun ref(value: RdfTerm?) = FindingRef.from(finding(value))

    @Test fun `all term kinds and absent values have separate identities`() {
        val values = listOf(null, string(""), Iri("urn:value"), string("urn:value"),
            BlankNode("value"), string(BlankNode("value").toString()),
            Literal("1", XSD.integer), string("1"), LangString("text", "en"),
            LangString("text", "en", Direction.LTR), LangString("text", "en", Direction.RTL))
        assertEquals(values.size, values.map(::ref).toSet().size)
        assertEquals(ref(LangString("text", "en")), ref(LangString("text", "EN")))
    }

    @Test fun `field delimiters and malformed unicode cannot create collisions`() {
        val base = finding()
        val first = base.copy(violation = base.violation.copy(message = "left\u001furn:shape", shapeUri = "urn:other"))
        val second = base.copy(violation = base.violation.copy(message = "left", shapeUri = "urn:shape\u001furn:other"))
        assertNotEquals(FindingRef.from(first), FindingRef.from(second))
        assertNotEquals(ref(string("\uD800")), ref(string("\uD801")))
        assertNotEquals(ref(string("\uD800")), ref(string("?")))
    }

    @Test fun `nested terms use the same stable blank node keys`() {
        fun keyed(id: String): FindingRef {
            val node = BlankNode(id)
            val value = TripleTerm(RdfTriple(node, Iri("urn:p"), TripleTerm(RdfTriple(node, Iri("urn:q"), string("x")))))
            return FindingRef.from(finding(value).copy(blankNodeKeys = mapOf(node to "stable-key")))
        }
        assertEquals(keyed("parser-one"), keyed("parser-two"))
        assertNotEquals(ref(TripleTerm(RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o")))),
            ref(TripleTerm(RdfTriple(Iri("urn:s"), Iri("urn:p"), string("urn:o")))))
    }

    @Test fun `complex paths and source constraints participate in identity`() {
        val base = finding()
        fun path(iri: String) = FindingRef.from(base.copy(violation = base.violation.copy(resultPathNode = Iri(iri))))
        assertNotEquals(path("urn:p"), path("urn:q"))
        assertNotEquals(FindingRef.from(base), FindingRef.from(base.copy(violation = base.violation.copy(sourceConstraint = Iri("urn:constraint")))))
    }

    @Test fun `path blank node labels cannot override data blank node keys`() {
        fun withPath(label: String): FindingRef {
            val focus = BlankNode("shared")
            val path = BlankNode(label)
            val base = finding()
            return FindingRef.from(base.copy(blankNodeKeys = mapOf(focus to "data-key"),
                violation = base.violation.copy(focusNode = focus, resultPathNode = path,
                    resultPathTriples = listOf(RdfTriple(path, Iri("urn:inversePath"), Iri("urn:p"))))))
        }
        assertEquals(withPath("shared"), withPath("independent-parser-label"))
    }

    @Test fun `structural blank node keys preserve malformed unicode and result reifiers`() {
        val node = BlankNode("node")
        fun key(lexical: String) = BlankNodeKeys.compute(
            com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(RdfTriple(node, Iri("urn:p"), string(lexical)))),
            setOf(node),
        ).getValue(node)
        assertNotEquals(key("\uD800"), key("\uD801"))
        assertNotEquals(key("\uD800"), key("?"))
        val prefix = "x".repeat(2048)
        assertNotEquals(key(prefix + "\uD800"), key(prefix + "\uD801"))
        assertNotEquals(ref(string(prefix + "\uD800")), ref(string(prefix + "\uD801")))
        val base = finding()
        fun reified(iri: String) = FindingRef.from(base.copy(violation = base.violation.copy(
            context = mapOf(ValidationViolation.REIFIER_CONTEXT_KEY to Iri(iri)))))
        assertNotEquals(reified("urn:reifier:one"), reified("urn:reifier:two"))
    }

    @Test fun `report construction collects keys inside nested values and reifiers`() {
        fun reference(label: String): FindingRef {
            val node = BlankNode(label)
            val graph = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(
                RdfTriple(node, Iri("urn:description"), string("stable structure"))))
            val violation = finding(TripleTerm(RdfTriple(Iri("urn:s"), Iri("urn:p"), node))).violation.copy(
                context = mapOf(ValidationViolation.REIFIER_CONTEXT_KEY to node))
            val statistics = ValidationStatistics(1, 1, 1, 1, 1, emptyMap(), emptyMap(), emptyMap(), Duration.ZERO)
            val raw = ValidationReport(false, listOf(violation), emptyList(), statistics, Duration.ZERO, 1, 1)
            val keys = QualityReport.blankNodeKeys(raw, graph, null)
            assertTrue(node in keys)
            return FindingRef.from(finding().copy(violation = violation, blankNodeKeys = keys))
        }
        assertEquals(reference("first-parser"), reference("second-parser"))
    }
}
