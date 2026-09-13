package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for SHACL Core / SHACL-SPARQL semantics of the native engine (audit findings). */
class NativeSpecRegressionTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun validate(data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidatorProvider().createValidator(config).validate(g(data), g(shapes))
    private fun ex(local: String) = Iri("http://example.org/$local")

    // --- Finding 1: recursion on (focus, shape) pairs, not shapes alone ---

    @Test fun `recursive sh node over an acyclic data chain conforms`() {
        val shapes = """
            ex:PersonShape a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
        """
        val report = validate("ex:a ex:knows ex:b . ex:b ex:knows ex:c .", shapes)
        assertTrue(report.isValid, report.violations.toString())
    }

    @Test fun `recursive sh node over cyclic data terminates and still propagates real failures`() {
        val shapes = """
            ex:PersonShape a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
        """
        assertTrue(validate("ex:a ex:name 'a' ; ex:knows ex:b . ex:b ex:name 'b' ; ex:knows ex:a .", shapes).isValid)
        val bad = validate("ex:a ex:name 'a' ; ex:knows ex:b . ex:b ex:knows ex:c . ex:c ex:name 'c' .", shapes)
        assertFalse(bad.isValid)
        assertTrue(bad.violations.any { it.constraint.constraintType == ConstraintType.NODE && it.value == ex("b") })
    }

    // --- Finding 2: any result makes the report non-conforming ---

    @Test fun `warnings and infos make the report non-conforming`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:severity sh:Warning ; sh:class ex:C ."
        val report = validate("ex:a ex:p 1 .", shapes)
        assertFalse(report.isValid)
        assertFalse(report.hasViolations)
        val conforms = report.toShaclValidationReportRdf().getTriples().single { it.predicate == SHACL.conforms }.obj
        assertEquals("false", (conforms as Literal).lexical)
    }

    // --- Finding 3: property shapes default to sh:Violation ---

    @Test fun `property shapes do not inherit node shape severity`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:severity sh:Info ;
              sh:property [ sh:path ex:p ; sh:minCount 1 ] .
        """
        val v = validate("ex:a ex:q 1 .", shapes).violations.single()
        assertEquals(ViolationSeverity.VIOLATION, v.severity)
    }

    // --- Finding 4: multi-valued parameters ---

    @Test fun `every sh pattern and sh hasValue value is a constraint`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:pattern "^a" , "b${'$'}" ] ;
              sh:property [ sh:path ex:q ; sh:hasValue 1 , 2 ] .
        """
        val report = validate("ex:a ex:p 'ax' ; ex:q 1 .", shapes)
        assertEquals(1, report.violations.count { it.constraint.constraintType == ConstraintType.PATTERN })
        assertEquals(1, report.violations.count { it.constraint.constraintType == ConstraintType.HAS_VALUE })
    }

    @Test fun `single-valued parameters with several values are rejected`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 , 2 ] ."
        assertThrows(ShaclValidationException::class.java) { validate("ex:a ex:p 1 .", shapes) }
    }

    // --- Finding 5: literal value nodes can conform to nested shapes ---

    @Test fun `literal values conform to nested node qualified and someValue shapes`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:node [ sh:datatype xsd:integer ] ] ;
              sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ sh:datatype xsd:integer ] ; sh:qualifiedMinCount 1 ] ;
              sh:property [ sh:path ex:p ; sh:someValue [ sh:datatype xsd:integer ] ] ;
              sh:property [ sh:path ex:p ; sh:shape [ sh:datatype xsd:integer ] ] .
        """
        assertTrue(validate("ex:a ex:p 5 .", shapes).isValid)
        assertEquals(4, validate("ex:a ex:p 'x' .", shapes).violations.size)
    }

    // --- Finding 6: qualifiedValueShapesDisjoint ---

    @Test fun `disjoint qualified shapes exclude values conforming to siblings for min and max`() {
        val shapes = """
            ex:Hand a sh:NodeShape ; sh:targetNode ex:h ;
              sh:property [ sh:path ex:digit ; sh:qualifiedValueShape [ sh:class ex:Thumb ] ;
                            sh:qualifiedMaxCount 0 ; sh:qualifiedValueShapesDisjoint true ] ;
              sh:property [ sh:path ex:digit ; sh:qualifiedValueShape [ sh:class ex:Finger ] ;
                            sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] .
        """
        // The only digit is both a Thumb and a Finger: it counts for neither shape.
        val report = validate("ex:h ex:digit ex:d . ex:d a ex:Thumb , ex:Finger .", shapes)
        assertEquals(listOf(ConstraintType.QUALIFIED_MIN_COUNT), report.violations.map { it.constraint.constraintType })
        assertNull(report.violations.single().value)
    }

    // --- Finding 8: deactivated and constraint-free shapes ---

    @Test fun `every node conforms to deactivated and empty shapes`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:node ex:Empty , ex:Deactivated , ex:NoTriplesAtAll .
            ex:Empty a sh:NodeShape .
            ex:Deactivated a sh:NodeShape ; sh:deactivated true ; sh:class ex:Missing .
        """
        assertTrue(validate("ex:a ex:p 1 .", shapes).isValid)
        val negated = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:not ex:Empty . ex:Empty a sh:NodeShape ."
        assertEquals(ConstraintType.NOT, validate("ex:a ex:p 1 .", negated).violations.single().constraint.constraintType)
    }

    // --- Finding 9: implicit class targets ---

    @Test fun `rdfs Class with shape parameters is an implicit class target`() {
        val shapes = "ex:Person a rdfs:Class ; sh:property [ sh:path ex:name ; sh:minCount 1 ] ."
        val report = validate("ex:p a ex:Person .", shapes)
        assertEquals(ex("p"), report.violations.single().focusNode)
    }

    // --- Finding 11: SHACL-SPARQL ---

    @Test fun `sparql constraints substitute PATH use prefixes emit one result per row and template messages`() {
        val shapes = """
            ex: sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/"^^xsd:anyURI ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ;
                sh:sparql [ sh:prefixes ex: ; sh:message "bad {?value} on {${'$'}this}" ;
                  sh:select "SELECT ${'$'}this ?value WHERE { ${'$'}this ${'$'}PATH ?value . FILTER (?value > 2) }" ] ] ;
              sh:sparql [ sh:deactivated true ; sh:select "SELECT ${'$'}this WHERE { }" ] .
        """
        val report = validate("ex:a ex:p 1 , 3 , 4 .", shapes)
        assertEquals(2, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.path == listOf(ex("p")) })
        assertEquals(setOf("3", "4"), report.violations.map { (it.value as Literal).lexical }.toSet())
        assertTrue(report.violations.any { it.message == "bad 3 on http://example.org/a" }, report.violations.map { it.message }.toString())
    }

    @Test fun `sparql constraints query the data graph not the shapes graph`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select "SELECT ${'$'}this WHERE { ?s a <http://www.w3.org/ns/shacl#NodeShape> }" ] .
        """
        assertTrue(validate("ex:a ex:p 1 .", shapes).isValid)
    }

    @Test fun `unsupported sparql pre-binding constructs fail validation`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"SELECT ${'$'}this WHERE { MINUS { ${'$'}this ex:p 1 } }\" ] ."
        assertThrows(ShaclValidationException::class.java) { validate("ex:a ex:p 1 .", shapes) }
    }

    // --- Finding 12: sh:pattern flags and invalid patterns ---

    @Test fun `pattern q and x flags are honoured and invalid patterns fail at compile time`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:pattern "a.b" ; sh:flags "q" ] ;
              sh:property [ sh:path ex:q ; sh:pattern "^a b c${'$'}" ; sh:flags "x" ] .
        """
        assertTrue(validate("ex:a ex:p 'xa.bx' ; ex:q 'abc' .", shapes).isValid)
        assertFalse(validate("ex:a ex:p 'axb' ; ex:q 'abc' .", shapes).isValid)
        val broken = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:pattern \"(\" ] ."
        assertThrows(ShaclValidationException::class.java) { validate("ex:z ex:p 'x' .", broken) }
    }

    // --- Finding 16: uniqueValuesFor bounded results ---

    @Test fun `uniqueValuesFor respects the result cap while marking the report invalid`() {
        val data = (1..20).joinToString(" ") { "ex:n$it a ex:T ; ex:key 'same' ." }
        val shapes = "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor ( ex:key ) ."
        val report = validate(data, shapes, ValidationConfig(maxViolations = 5))
        assertFalse(report.isValid)
        assertTrue(report.violationsTruncated)
        assertEquals(5, report.violations.size)
    }

    // --- Finding 17: report fidelity ---

    @Test fun `sh message with language tags is emitted as result message`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:class ex:C ; sh:message \"Mauvais\"@fr , \"Wrong\"@en ."
        val v = validate("ex:a ex:p 1 .", shapes).violations.single()
        assertEquals(setOf(LangString("Mauvais", "fr"), LangString("Wrong", "en")), v.resultMessages.toSet())
        val msgs = validate("ex:a ex:p 1 .", shapes).toShaclValidationReportRdf().getTriples().filter { it.predicate == SHACL.resultMessage }
        assertEquals(2, msgs.size)
    }

    @Test fun `closed reports one result per offending triple and disjoint every shared value`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ;
              sh:property [ sh:path ex:p ; sh:disjoint ex:q ] ;
              sh:property [ sh:path ex:q ] .
        """
        val report = validate("ex:a ex:p 1 , 2 , 3 ; ex:q 1 , 2 ; ex:x 7 , 8 .", shapes)
        assertEquals(2, report.violations.count { it.constraint.constraintType == ConstraintType.CLOSED })
        assertEquals(2, report.violations.count { it.constraint.constraintType == ConstraintType.DISJOINT })
    }

    @Test fun `complex result paths are exported in full and value is omitted for minCount and hasValue`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path [ sh:inversePath ex:parent ] ; sh:minCount 1 ; sh:hasValue ex:z ] .
        """
        val report = validate("ex:a ex:p 1 .", shapes)
        assertEquals(2, report.violations.size)
        assertTrue(report.violations.all { it.value == null })
        val rdf = report.toShaclValidationReportRdf()
        val pathNodes = rdf.getTriples().filter { it.predicate == SHACL.resultPath }.map { it.obj }
        assertTrue(pathNodes.all { p -> p is BlankNode && rdf.getTriples().any { it.subject == p && it.predicate == SHACL.inversePath && it.obj == ex("parent") } })
    }

    @Test fun `logical constraints report the value node`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:and ( [ sh:class ex:C ] ) ; sh:xone ( [ sh:class ex:C ] ) ; sh:not [ sh:nodeKind sh:IRI ] ."
        val report = validate("ex:a ex:p 1 .", shapes)
        assertEquals(3, report.violations.size)
        assertTrue(report.violations.all { it.value == ex("a") })
    }

    // --- Finding 21: value nodes are sets; closed ignores complex paths ---

    @Test fun `sh equals uses set semantics for paths yielding duplicates`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path [ sh:alternativePath ( ex:p ex:p ) ] ; sh:equals ex:p ; sh:class ex:C ] .
        """
        val report = validate("ex:a ex:p ex:b .", shapes)
        assertEquals(listOf(ConstraintType.CLASS), report.violations.map { it.constraint.constraintType })
    }

    @Test fun `closed shapes only allow predicates of direct IRI property paths`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ;
              sh:property [ sh:path ( ex:p ex:q ) ] .
        """
        assertEquals(2, validate("ex:a ex:p ex:b . ex:a ex:q 1 .", shapes).violations.count { it.constraint.constraintType == ConstraintType.CLOSED })
    }
}
