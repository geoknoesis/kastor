package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Re-audit findings on SHACL Core semantics, unsupported features and resource bounds of the native engine. */
class NativeReauditSemanticsTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun validate(data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        validate(g(data), shapes, config)
    private fun validate(data: RdfGraph, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidatorProvider().createValidator(config).validate(data, g(shapes))

    private fun assertUnsupported(data: String, shapes: String) {
        val e = assertThrows(ShaclValidationException::class.java) { validate(data, shapes) }
        assertTrue(e.message!!.contains("Unsupported SHACL feature"), e.message)
    }

    // --- property shapes with targets ---

    @Test fun `property shape with targets applies nested property shapes once per value`() {
        val shapes = "ex:PS sh:targetNode ex:a ; sh:path ex:p ; sh:property [ sh:path ex:q ; sh:minCount 1 ] ."
        assertTrue(validate("ex:a ex:p ex:b . ex:b ex:q 1 .", shapes).isValid)
        val report = validate("ex:a ex:p ex:b .", shapes)
        assertEquals(listOf(ex("b")), report.violations.map { it.focusNode })
    }

    @Test fun `sh closed on a property shape is ignored`() {
        val shapes = "ex:PS sh:targetNode ex:a ; sh:path ex:p ; sh:closed true ; sh:minCount 1 ."
        assertTrue(validate("ex:a ex:p ex:b ; ex:r 1 .", shapes).isValid)
    }

    // --- closed shapes ---

    @Test fun `closed shapes allow predicates of deactivated property shapes`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ;
              sh:property [ sh:path ex:p ; sh:deactivated true ; sh:minCount 5 ] .
        """
        assertTrue(validate("ex:a ex:p 1 .", shapes).isValid)
    }

    // --- nodeKind ---

    @Test fun `repeated sh nodeKind values are each enforced`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:nodeKind sh:IRI , sh:Literal ] ."
        val report = validate("ex:a ex:p ex:b .", shapes)
        assertEquals(listOf(ConstraintType.NODE_KIND), report.violations.map { it.constraint.constraintType })
    }

    // --- targetWhere candidates ---

    @Test fun `targetWhere candidates include nodes that only occur as objects`() {
        val iriShapes = "ex:S a sh:NodeShape ; sh:targetWhere ex:W ; sh:class ex:C . ex:W a sh:NodeShape ; sh:in ( ex:b ) ."
        assertEquals(listOf(ex("b")), validate("ex:a ex:p ex:b .", iriShapes).violations.map { it.focusNode })

        val literalShapes = "ex:S a sh:NodeShape ; sh:targetWhere ex:W ; sh:maxLength 1 . ex:W a sh:NodeShape ; sh:datatype xsd:string ."
        val focus = validate("ex:a ex:p \"long\" .", literalShapes).violations.single().focusNode
        assertEquals("long", (focus as Literal).lexical)
    }

    // --- unsupported features fail explicitly ---

    @Test fun `targetWhere with a SPARQL select expression fails by default and can be ignored with a warning`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:class ex:C ;
              sh:targetWhere [ a sh:SelectExpression ; sh:selectExpression "SELECT ?this WHERE { ?this ex:p 1 }" ] .
        """
        assertUnsupported("ex:a ex:p 1 .", shapes)
        val report = validate("ex:a ex:p 1 .", shapes, ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING))
        assertTrue(report.isValid, report.violations.toString())
        assertTrue(report.warnings.any { it.message.contains("Unsupported SHACL feature") }, report.warnings.toString())
    }

    @Test fun `targetWhere with a SPARQL expression fails`() {
        assertUnsupported(
            "ex:a ex:p 1 .",
            "ex:S a sh:NodeShape ; sh:class ex:C ; sh:targetWhere [ a sh:SPARQLExprExpression ; sh:exprExpression \"?x ex:p 1\" ] .",
        )
    }

    @Test fun `node expressions in sh values sh expression and sh targetNode fail`() {
        assertUnsupported(
            "ex:a a ex:P .",
            "ex:S a sh:PropertyShape ; sh:targetClass ex:P ; sh:path ex:q ; sh:values [ sh:select \"SELECT ?q WHERE { }\" ] ; sh:minCount 1 .",
        )
        assertUnsupported("ex:a a ex:P .", "ex:S a sh:NodeShape ; sh:targetClass ex:P ; sh:expression false .")
        assertUnsupported(
            "ex:a a ex:P .",
            "ex:S a sh:NodeShape ; sh:targetNode [ sh:select \"SELECT ?x WHERE { ?x a ex:P }\" ] ; sh:class ex:Q .",
        )
    }

    @Test fun `sh nodeByExpression over a computed expression fails`() {
        assertUnsupported(
            "ex:a a ex:P .",
            "ex:S a sh:NodeShape ; sh:targetClass ex:P ; sh:nodeByExpression [ sh:select \"SELECT ?s WHERE { }\" ] .",
        )
    }

    @Test fun `sh nodeByExpression with a shape IRI is checked for every value of a property shape`() {
        val shapes = """
            ex:I a sh:NodeShape ; sh:targetNode ex:i ; sh:property ex:IP .
            ex:IP sh:path ex:assignedTo ; sh:nodeByExpression ex:AS .
            ex:AS a sh:NodeShape ; sh:property [ sh:path ex:email ; sh:minCount 1 ] .
        """
        val v = validate("ex:i ex:assignedTo ex:anon , ex:john . ex:john ex:email 'j@x' .", shapes).violations.single()
        assertEquals(ConstraintType.NODE_BY_EXPRESSION, v.constraint.constraintType)
        assertEquals(ex("i"), v.focusNode)
        assertEquals(ex("anon"), v.value)
        assertEquals(ex("AS"), v.sourceConstraint)
    }

    @Test fun `SPARQL-based constraint components fail when a shape uses them`() {
        val component = """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:lang ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (lang(${'$'}value) = ${'$'}lang) }" ] .
        """
        assertUnsupported("ex:a ex:p 'x' .", component + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:lang 'en' .")
        // Declared but unused components are harmless.
        assertTrue(validate("ex:a ex:p 'x' .", component + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind sh:IRI .").isValid)
    }

    @Test fun `SHACL 1_2 expression functions called from SPARQL fail explicitly`() {
        val shapes = """
            ex:twice a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:sparqlExpr "CONCAT(${'$'}arg0, ${'$'}arg0)" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${'$'}this ?value WHERE { BIND (<http://example.org/twice>("x") AS ?value) }''' ] .
        """
        assertUnsupported("ex:a ex:p 1 .", shapes)
    }

    @Test fun `prefix declarations of the shapes graph apply to SPARQL queries without sh prefixes`() {
        val shapes = """
            ex:G a sh:ShapesGraph ; sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/" ] .
            ex:Other sh:declare [ sh:prefix "ex" ; sh:namespace "urn:ignored:" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
              sh:sparql [ sh:select '''SELECT ${'$'}this ?value WHERE { ${'$'}this ex:p ?value . FILTER (?value > 1) }''' ] .
        """
        assertEquals(listOf(ex("b")), validate("ex:a ex:p 1 . ex:b ex:p 2 .", shapes).violations.map { it.focusNode })
    }

    // --- resource bounds ---

    @Test fun `uniqueValuesFor with 100000 duplicates finishes quickly and stays capped`() {
        val data = Rdf.graph {
            for (i in 0 until 100_000) {
                ex("n$i") - RDF.type - ex("T")
                ex("n$i") - ex("key") - string("same")
            }
        }
        val report = assertTimeoutPreemptively<ValidationReport>(Duration.ofSeconds(60)) {
            validate(data, "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor ( ex:key ) .")
        }
        assertFalse(report.isValid)
        assertTrue(report.violationsTruncated)
        assertEquals(ValidationConfig.default().maxViolations, report.violations.size)
    }

    @Test fun `nested zeroOrOne paths over a self loop do not blow up`() {
        val path = (1..30).joinToString(" ") { "[ sh:zeroOrOnePath ex:p ]" }
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ( $path ) ; sh:minCount 1 ; sh:maxCount 1 ] ."
        val report = assertTimeoutPreemptively<ValidationReport>(Duration.ofSeconds(20)) { validate("ex:a ex:p ex:a .", shapes) }
        assertTrue(report.isValid, report.violations.toString())
    }

    @Test fun `path value node sets are capped by configuration`() {
        val data = Rdf.graph { for (i in 0 until 500) ex("c$i") - ex("next") - ex("c${i + 1}") }
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:c0 ; sh:property [ sh:path [ sh:zeroOrMorePath ex:next ] ; sh:minCount 1 ] ."
        assertTrue(validate(data, shapes).isValid)
        val e = assertThrows(ShaclValidationException::class.java) { validate(data, shapes, ValidationConfig(maxPathValueNodes = 100)) }
        assertTrue(e.message!!.contains("maxPathValueNodes"), e.message)
    }

    @Test fun `zeroOrMore path from a literal value node yields the literal`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:node [ sh:property [ sh:path [ sh:zeroOrMorePath ex:q ] ; sh:minCount 1 ] ] ] .
        """
        assertTrue(validate("ex:a ex:p 'literal' .", shapes).isValid)
    }

    @Test fun `blank node focus nodes are kept by value node sets`() {
        val report = validate("ex:a ex:p [ ex:q 1 ] .", "ex:S a sh:NodeShape ; sh:targetObjectsOf ex:p ; sh:property [ sh:path ex:q ; sh:maxCount 0 ] .")
        assertTrue(report.violations.single().focusNode is BlankNode)
    }
}
