package com.geoknoesis.kastor.ontoquality.catalog

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Detectors that scan the whole graph must still report each offender: two offenders give two findings whose
 * focus node (or, for the predicate scan, value), message and ref differ.
 */
class OffenderFocusTest {

    private val prefixes =
        """
        @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl:  <http://www.w3.org/2002/07/owl#> .
        @prefix ex:   <http://example.org/ns#> .
        <http://example.org/ns> a owl:Ontology .
        """.trimIndent()

    private val checker by lazy {
        QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.OWL_QUALITY).build()
    }

    private fun findings(shape: String, body: String): List<QualityFinding> {
        val graph = Rdf.parse(prefixes + "\n" + body.trimIndent(), RdfFormat.TURTLE)
        return checker.check(graph).findings.filter { it.violation.shapeUri == "http://example.org/owl-quality-shacl#$shape" }
    }

    private fun ex(local: String): String = "http://example.org/ns#$local"

    /** One finding per offender, with distinct refs, each naming its offender in the message. */
    private fun assertOnePerOffender(found: List<QualityFinding>, offenders: Set<String>, offender: (QualityFinding) -> String) {
        assertEquals(offenders, found.map(offender).toSet(), "offenders of ${found.map { it.violation.message }}")
        assertEquals(offenders.size, found.size, "one finding per offender: ${found.map { it.violation.message }}")
        assertEquals(found.size, found.map { FindingRef.from(it).hexSha256 }.toSet().size, "distinct refs")
        for (f in found) {
            assertTrue(f.violation.message.contains(offender(f)), "message names the offender: ${f.violation.message}")
        }
    }

    private fun focus(f: QualityFinding): String = (f.violation.focusNode as? Iri)?.value ?: f.violation.focusNode.toString()

    private fun value(f: QualityFinding): String = (f.violation.value as? Iri)?.value ?: f.violation.value.toString()

    @Test
    fun `P27 reports each property with a mismatched equivalent`() {
        val found =
            findings(
                "WrongEquivalentPropertiesShape",
                """
                ex:A a owl:Class . ex:B a owl:Class .
                ex:p1 a owl:ObjectProperty ; rdfs:domain ex:A ; owl:equivalentProperty ex:q1 .
                ex:q1 a owl:ObjectProperty ; rdfs:domain ex:B .
                ex:p2 a owl:ObjectProperty ; rdfs:range ex:A ; owl:equivalentProperty ex:q2 .
                ex:q2 a owl:ObjectProperty ; rdfs:range ex:B .
                """,
            )
        assertOnePerOffender(found, setOf(ex("p1"), ex("p2")), ::focus)
        assertEquals(setOf(ex("q1"), ex("q2")), found.map(::value).toSet())
    }

    @Test
    fun `P34 reports each undeclared class`() {
        val found =
            findings(
                "UntypedClassShape",
                """
                ex:A a owl:Class ; rdfs:subClassOf ex:Undeclared1 .
                ex:p a owl:ObjectProperty ; rdfs:range ex:Undeclared2 .
                """,
            )
        assertOnePerOffender(found, setOf(ex("Undeclared1"), ex("Undeclared2")), ::focus)
    }

    @Test
    fun `P35 reports each undeclared predicate once`() {
        val found =
            findings(
                "UntypedPropertyShape",
                """
                ex:A a owl:Class .
                ex:a ex:undeclared1 ex:b . ex:b ex:undeclared1 ex:c .
                ex:a ex:undeclared2 "x" .
                """,
            )
        assertOnePerOffender(found, setOf(ex("undeclared1"), ex("undeclared2")), ::value)
    }

    @Test
    fun `P20 reports each entity with malformed annotations`() {
        val found =
            findings(
                "MisusedDocumentationAnnotationsShape",
                """
                ex:A a owl:Class ; rdfs:label "" .
                ex:p a owl:ObjectProperty ; rdfs:label "the same text" ; rdfs:comment "the same text" .
                """,
            )
        assertOnePerOffender(found, setOf(ex("A"), ex("p")), ::focus)
    }

    @Test
    fun `P40 reports each entity minted in a foreign namespace`() {
        val found =
            findings(
                "NamespaceMismatchShape",
                """
                <http://other.example.com/vocab#Person> a owl:Class .
                <http://other.example.com/vocab#knows> a owl:ObjectProperty .
                ex:Local a owl:Class .
                """,
            )
        assertOnePerOffender(found, setOf("http://other.example.com/vocab#Person", "http://other.example.com/vocab#knows"), ::focus)
    }

    @Test
    fun `P01 reports each IRI that is both class and property`() {
        val found =
            findings(
                "PolysemousClassAndPropertyShape",
                """
                ex:both1 a owl:Class , owl:ObjectProperty .
                ex:both2 a owl:Class , owl:DatatypeProperty .
                """,
            )
        assertOnePerOffender(found, setOf(ex("both1"), ex("both2")), ::focus)
    }

    @Test
    fun `deprecated reference reports each deprecated entity still in use`() {
        val found =
            findings(
                "DeprecatedNotInUseShape",
                """
                ex:Old1 a owl:Class ; owl:deprecated true .
                ex:Old2 a owl:Class ; owl:deprecated true .
                ex:New1 a owl:Class ; rdfs:subClassOf ex:Old1 .
                ex:New2 a owl:Class ; rdfs:subClassOf ex:Old2 .
                """,
            )
        assertOnePerOffender(found, setOf(ex("Old1"), ex("Old2")), ::focus)
        assertEquals(setOf(ex("New1"), ex("New2")), found.map(::value).toSet())
    }
}
