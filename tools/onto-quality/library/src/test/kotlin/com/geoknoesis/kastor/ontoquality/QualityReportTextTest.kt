package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The plain-text report names the entity of every finding, and says so when the engine cut the result list. */
class QualityReportTextTest {

    private fun checker(config: ValidationConfig = ValidationConfig(maxViolations = Int.MAX_VALUE)) =
        QualityChecker.builder(ShaclValidation.validator(config)).addCatalog(BundledCatalogs.OWL_QUALITY).build()

    private val prefixes =
        """
        @prefix ex: <http://example.org/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        <http://example.org/onto> a owl:Ontology ; rdfs:label "o" ; rdfs:comment "c" .
        """.trimIndent()

    private fun parse(turtle: String) = Rdf.parse("$prefixes\n$turtle", RdfFormat.TURTLE)

    @Test
    fun `every finding line names its focus node`() {
        val report = checker().check(parse("ex:Alpha a owl:Class ; rdfs:comment \"a\" .\nex:Beta a owl:Class ; rdfs:comment \"b\" ."))
        val text = report.describeText()
        val labelLines = text.lines().withIndex().filter { "Class has no rdfs:label." in it.value }
        assertEquals(2, labelLines.size, text)
        val focusLines = labelLines.map { text.lines()[it.index + 1] }
        assertEquals(
            setOf("   Focus: <http://example.org/onto#Alpha>", "   Focus: <http://example.org/onto#Beta>"),
            focusLines.toSet(),
            text,
        )
    }

    @Test
    fun `path and value are shown when the result has them`() {
        val report = checker().check(parse("ex:p a owl:ObjectProperty ; rdfs:label \"p\" ; rdfs:domain ex:A , ex:B ; rdfs:range ex:A .\nex:A a owl:Class .\nex:B a owl:Class ."))
        val text = report.describeText()
        assertContains(text, "Path: <http://www.w3.org/2000/01/rdf-schema#domain>")
        val deprecated =
            checker().check(parse("ex:Old a owl:Class ; owl:deprecated true .\nex:New a owl:Class ; rdfs:subClassOf ex:Old .")).describeText()
        assertContains(deprecated, "Value: <http://example.org/onto#New>")
    }

    @Test
    fun `a blank focus node is shown by its stable key, not its parser label`() {
        val turtle = "[] a owl:Ontology ; rdfs:label \"anon\" .\nex:A a owl:Class ; rdfs:label \"A\" ; rdfs:comment \"a\" ."
        val first = checker().check(parse(turtle)).describeText()
        val anonymous = first.lines().filter { it.startsWith("   Focus: _:") }
        assertTrue(anonymous.isNotEmpty(), first)
        assertTrue(anonymous.all { it.startsWith("   Focus: _:k") }, first)
        val second = checker().check(parse(turtle)).describeText()
        assertEquals(first.lines().sorted(), second.lines().sorted(), "the text must not depend on parser blank-node labels")
    }

    @Test
    fun `a truncated report says so in text and Markdown`() {
        val classes = (1..12).joinToString("\n") { "ex:C$it a owl:Class ." }
        val report = checker(ValidationConfig(maxViolations = 5)).check(parse(classes))
        assertTrue(report.truncated)
        assertEquals(5, report.findings.size)
        assertContains(report.describeText(), "TRUNCATED")
        assertContains(report.describeMarkdown(), "truncated")
        val complete = checker().check(parse(classes))
        assertFalse(complete.truncated)
        assertFalse(complete.describeText().contains("TRUNCATED"))
    }
}
