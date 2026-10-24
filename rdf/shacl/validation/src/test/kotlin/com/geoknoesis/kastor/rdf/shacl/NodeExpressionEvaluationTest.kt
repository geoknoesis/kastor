package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** SHACL 1.2 SPARQL §6 node expressions evaluated at `sh:targetNode`, `sh:values` and `sh:defaultValue`. */
class NodeExpressionEvaluationTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private val d = "$"

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config) { repository(provider) }.validate(g(data), g(shapes))

    private val ontology = """
        <http://example.com/ns> a owl:Ontology ;
          sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.com/ns#"^^xsd:anyURI ] .
    """

    private fun fullNameShape(hasValue: String) = """
        ex:Person-fullName a sh:PropertyShape ; sh:targetClass ex:Person ; sh:path ex:fullName ;
          sh:values [ sh:prefixes <http://example.com/ns> ; sh:select '''
            SELECT ?fullName WHERE {
              ${d}this ex:firstName ?firstName . ${d}this ex:lastName ?lastName .
              BIND (CONCAT(?firstName, " ", ?lastName) AS ?fullName) } ''' ] ;
          sh:hasValue $hasValue .
        $ontology
        ex:JohnMuir a ex:Person ; ex:firstName "John" ; ex:lastName "Muir" .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `property values come from a select expression`(provider: String) {
        val ok = fullNameShape("\"John Muir\"")
        assertTrue(validate(provider, ok, ok).isValid)
        val bad = fullNameShape("\"John Doe\"")
        val v = validate(provider, bad, bad).violations.single()
        assertEquals(ex("JohnMuir"), v.focusNode)
        assertEquals(ConstraintType.HAS_VALUE, v.constraint.constraintType)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `values are unioned with path values`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values 2 ; sh:minCount 2 ] ."
        assertTrue(validate(provider, "ex:a ex:p 1 .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `constant values and default values produce value nodes`(provider: String) {
        val values = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values ex:x ; sh:hasValue ex:x ] ."
        assertTrue(validate(provider, "ex:a ex:q 1 .", values).isValid)
        val default = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:defaultValue 1 ; sh:hasValue 1 ] ."
        assertTrue(validate(provider, "ex:a ex:q 1 .", default).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `default value applies only when there are no values`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a, ex:b ;
              sh:property [ sh:path ex:p ; sh:defaultValue "d" ; sh:hasValue "d" ] .
        """
        val report = validate(provider, """ex:b ex:p "x" .""", shapes)
        assertEquals(listOf(ex("b")), report.violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `default value select expression applies when there are no values`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:defaultValue [ sh:select "SELECT ?v { BIND (7 AS ?v) }" ] ; sh:hasValue 7 ] .
        """
        assertTrue(validate(provider, "ex:a ex:q 1 .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `unbound and duplicate solutions`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:maxCount 1 ; sh:minCount 1 ; sh:hasValue "s" ;
                sh:values [ sh:select '''SELECT ?v { { BIND ("s" AS ?v) } UNION { BIND ("s" AS ?v) } UNION { BIND (1 AS ?other) } }''' ] ] .
        """
        assertTrue(validate(provider, "ex:a ex:q 1 .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sparqlExpr value`(provider: String) {
        val shapes = """
            ex:S a sh:PropertyShape ; sh:targetNode ex:Test ; sh:path ex:uriLength ;
              sh:values [ sh:prefixes <http://example.com/ns> ; sh:sparqlExpr "STRLEN(STR(${d}this))" ] ;
              sh:hasValue 26 .
            $ontology
        """
        assertTrue(validate(provider, "ex:Test ex:q 1 .", shapes).isValid)
    }

    private val benjamin = """
        ex:ChildShape a sh:NodeShape ;
          sh:targetNode [ sh:select '''
            PREFIX ex: <http://example.com/ns#>
            SELECT ?person WHERE { ?person a ex:Person . ?person ex:age ?age . FILTER (?age < 18) }''' ] ;
          sh:property [ sh:path ex:driversLicense ; sh:maxCount 0 ] .
        ex:Benjamin a ex:Person ; ex:driversLicense "123" ; ex:age 17 .
        ex:Klaus a ex:Person ; ex:driversLicense "456" ; ex:age 48 .
        ex:Bernd a ex:Person ; ex:driversLicense "789" .
    """

    /** Shapes graph and data graph are the same graph, as in W3C targetNode-select-001. */
    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `targetNode select targets only matching nodes`(provider: String) {
        val report = validate(provider, benjamin, benjamin)
        assertEquals(listOf(ex("Benjamin")), report.violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `targetNode select pre-binds the shape node`(provider: String) {
        val shapes = """
            ex:Self a sh:NodeShape ;
              sh:targetNode [ sh:select "SELECT ?s { BIND (${d}this AS ?s) }" ] ;
              sh:property [ sh:path ex:p ; sh:minCount 1 ] .
        """
        val report = validate(provider, "ex:a ex:q 1 .", shapes)
        assertEquals(listOf(ex("Self")), report.violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a failing expression fails validation`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:values [ sh:sparqlExpr "1 +" ] ] .
        """
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:q 1 .", shapes) }
    }

    private val threeNodes = """sh:select '''SELECT ?v { { BIND (1 AS ?v) } UNION { BIND (2 AS ?v) } UNION { BIND (3 AS ?v) } }'''"""

    private fun assertCapped(provider: String, data: String, shapes: String) {
        val e = assertThrows(ShaclValidationException::class.java) {
            validate(provider, data, shapes, ValidationConfig(maxPathValueNodes = 2))
        }
        assertTrue(e.message!!.contains("maxPathValueNodes=2"), e.message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sh values select is capped by maxPathValueNodes`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values [ $threeNodes ] ] ."
        assertCapped(provider, "ex:a ex:q 1 .", shapes)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `path values plus sh values are capped by maxPathValueNodes`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values 3 ] ."
        assertCapped(provider, "ex:a ex:p 1, 2 .", shapes)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `targetNode select is capped by maxPathValueNodes`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode [ $threeNodes ] ; sh:property [ sh:path ex:p ; sh:minCount 0 ] ."
        assertCapped(provider, "ex:a ex:q 1 .", shapes)
    }
}
