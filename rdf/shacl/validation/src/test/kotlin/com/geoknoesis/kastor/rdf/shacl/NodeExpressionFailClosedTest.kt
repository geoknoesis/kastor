package com.geoknoesis.kastor.rdf.shacl

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

/**
 * A deliberate deviation from SHACL 1.2 Node Expressions §3.3 / §5: a conformance check of a node expression that the
 * engine could not decide (a final undefined answer, a validation error, an ill-formed or unsupported candidate shape)
 * stops validation, even under a function that absorbs SPARQL errors (`sparql:coalesce`, the unselected branch of
 * `sparql:if`, `sparql:logical-or`, ...). Ordinary evaluation errors stay absorbable.
 */
class NodeExpressionFailClosedTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String) =
        NativeShaclValidator(ValidationConfig.default()) { repository(provider) }.validate(g(data), g(shapes))

    private val d = "$"

    /** A paradox: a node conforms to ex:P iff it does not, so every answer is undefined. */
    private val paradox = "ex:P a sh:NodeShape ; sh:not ex:P .\n"

    /** Whether the focus node conforms to the paradox: a check the engine cannot decide. */
    private val check = """[ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:P ) ]"""

    private val data = "ex:a ex:age 66 ."

    private fun assertStops(provider: String, shapes: String, data: String = this.data) {
        val error = assertThrows(ShaclValidationException::class.java, { validate(provider, data, paradox + shapes) }, shapes)
        assertTrue("undefined" in error.message.orEmpty() || "cannot be decided" in error.message.orEmpty(), "$shapes: ${error.message}")
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an undecided check at sh expression stops validation under every absorbing function`(provider: String) {
        val expressions = listOf(
            check,
            "[ sparql:coalesce ( $check true ) ]",
            "[ sparql:if ( true true $check ) ]",
            "[ sparql:if ( false $check true ) ]",
            "[ sparql:coalesce ( [ sparql:bound ( $check ) ] true ) ]",
            "[ sparql:logical-or ( true $check ) ]",
            "[ sparql:logical-and ( false $check ) ]",
            "[ sparql:coalesce ( [ sparql:logical-not ( $check ) ] true ) ]",
            "[ sparql:coalesce ( [ shnex:if $check ; shnex:then true ; shnex:else true ] true ) ]",
        )
        for (expr in expressions) assertStops(provider, "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression $expr .")
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `every shape-reading function stops validation under coalesce`(provider: String) {
        val reads = listOf(
            "[ shnex:exists [ shnex:filterShape ex:P ; shnex:nodes ( ex:a ) ] ]",
            "[ shnex:exists [ shnex:nodesMatching ex:P ] ]",
            "[ shnex:exists [ shnex:findFirst ex:P ; shnex:nodes ( ex:a ) ] ]",
            "[ shnex:matchAll ex:P ; shnex:nodes ( ex:a ) ]",
            // A custom function whose body validates.
            """[ ex:g ( [ shnex:var "focusNode" ] ) ]""",
            // An argument of a custom function, after an argument with an ordinary error.
            "[ ex:k ( [ sparql:abs ( ( 1 2 ) ) ] $check ) ]",
        )
        val g = """
            ex:g a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ shnex:conformsToShape ( [ shnex:arg 0 ] ex:P ) ] .
            ex:k a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg1 ] ;
              sh:bodyExpression [ shnex:arg 0 ] .
        """
        for (read in reads) assertStops(provider, "$g ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sparql:coalesce ( $read true ) ] .")
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an undecided check stops validation at every evaluating position`(provider: String) {
        val absorbed = "[ sparql:coalesce ( [ sparql:if ( $check 1 1 ) ] 200 ) ]"
        val positions = listOf(
            // sh:values, sh:targetNode, sh:nodeByExpression.
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:v ; sh:values $absorbed ; sh:datatype xsd:string ] .",
            "ex:S a sh:NodeShape ; sh:targetNode [ sparql:coalesce ( [ shnex:conformsToShape ( ex:a ex:P ) ] ex:a ) ] ; sh:class ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ sparql:coalesce ( [ sparql:if ( $check ex:T ex:T ) ] ex:T ) ] . ex:T sh:class ex:C .",
            // sh:deactivated of a node shape, a property shape, a SPARQL-based constraint and a reifier.
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated [ sparql:coalesce ( $check true ) ] ; sh:class ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:age ; sh:maxCount 0 ; sh:deactivated [ sparql:coalesce ( $check true ) ] ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select 'SELECT ${d}this WHERE { }' ; sh:deactivated [ sparql:coalesce ( $check true ) ] ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:age ; sh:maxCount 0 {| sh:deactivated [ sparql:coalesce ( $check true ) ] |} ] .",
            // A Dynamic SHACL parameter.
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:age ; sh:maxInclusive $absorbed ] .",
            // A computed parameter of a SPARQL-based constraint component.
            """
            ex:MaxAgeComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:maxAge ; sh:datatype xsd:integer ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (${d}value <= ${d}maxAge) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:age ; ex:maxAge $absorbed ] .
            """,
        )
        for (shapes in positions) assertStops(provider, shapes)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an undecided check through SPARQL stops validation under coalesce`(provider: String) {
        val f = """
            ex:f a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ shnex:conformsToShape ( [ shnex:arg 0 ] ex:P ) ] .
            ex:h a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sparql:coalesce ( [ shnex:conformsToShape ( [ shnex:arg 0 ] ex:P ) ] true ) ] .
        """
        val shapes = listOf(
            // A SPARQL node expression whose function call fails the query, under coalesce.
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sparql:coalesce ( [ sh:sparqlExpr '''<http://example.com/ns#f>(${d}this)''' ] true ) ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sparql:coalesce ( [ sh:select '''SELECT ?x WHERE { BIND (<http://example.com/ns#f>(${d}this) AS ?x) }''' ] true ) ] .",
            // A function body that absorbs the failure itself, called from sh:sparql and from a node expression.
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''SELECT ${d}this WHERE { FILTER (!<http://example.com/ns#h>(${d}this)) }''' ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sh:sparqlExpr '''<http://example.com/ns#h>(${d}this)''' ] .",
        )
        for (s in shapes) assertStops(provider, f + s)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an ill-formed or unsupported candidate shape read through coalesce stops validation`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:expression [ sparql:coalesce ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] [ shnex:pathValues ex:shape ] ) ] true ) ] .
            ex:Good a sh:NodeShape ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            ex:Malformed sh:datatype "not a datatype IRI" .
            ex:Unsupported sh:nodeKind sh:IRI ; sh:expression [ ex:undeclaredFunction 1 ] .
        """
        for (bad in listOf("Malformed", "Unsupported")) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:shape ex:$bad .", shapes) }, bad)
            assertTrue(bad in error.message.orEmpty(), "$bad: ${error.message}")
        }
        // A decided check is not affected: Good fails, so the expression is false.
        assertEquals(1, validate(provider, "ex:a ex:shape ex:Good .", shapes).violations.size)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a nested validation error read through coalesce stops validation`(provider: String) {
        // Validating against ex:Q fails: its own expression is not a single node.
        val shapes = """
            ex:Q a sh:NodeShape ; sh:expression [ sparql:abs ( ( 1 2 ) ) ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:expression [ sparql:coalesce ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Q ) ] true ) ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, data, shapes) }
        assertTrue("ns#Q" in error.message.orEmpty(), error.message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ordinary evaluation errors are still absorbed`(provider: String) {
        // sparql:abs of two nodes is an error that coalesce skips (§5): "ok" is the value.
        val values = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:none ; sh:values [ sparql:coalesce ( [ sparql:abs ( ( 1 2 ) ) ] "ok" ) ] ; sh:in ( "ok" ) ; sh:minCount 1 ] .
        """
        assertTrue(validate(provider, data, values).isValid)
        val absorbed = listOf(
            "[ sparql:coalesce ( [ sparql:abs ( ( 1 2 ) ) ] true ) ]",
            "[ sparql:coalesce ( [ sparql:abs ( \"x\" ) ] true ) ]",
            "[ sparql:if ( true true [ sparql:abs ( ( 1 2 ) ) ] ) ]",
            "[ sparql:logical-or ( true [ sparql:abs ( \"x\" ) ] ) ]",
            "[ sparql:coalesce ( [ sh:sparqlExpr '''1 / 0''' ] true ) ]",
            // A decided check under coalesce keeps its answer.
            "[ sparql:coalesce ( [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:Old ) ] false ) ]",
        )
        for (expr in absorbed) {
            val shapes = "ex:Old sh:property [ sh:path ex:age ; sh:minInclusive 18 ] . ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression $expr ."
            assertTrue(validate(provider, data, shapes).isValid, expr)
        }
        // A function body with an ordinary error is a SPARQL error of its call, which COALESCE skips.
        val bad = """
            ex:bad a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sparql:abs ( ( 1 2 ) ) ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this WHERE { FILTER (!COALESCE(<http://example.com/ns#bad>(${d}this), true)) }''' ] ;
              sh:expression [ sparql:coalesce ( [ sh:sparqlExpr '''COALESCE(<http://example.com/ns#bad>(${d}this), true)''' ] false ) ] .
        """
        assertTrue(validate(provider, data, bad).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a decidable cycle read through coalesce stays decided`(provider: String) {
        // X conforms: Y reads X back but fails its sh:minCount on its own, so not(conformsToShape(x, Y)) is true.
        val shapes = """
            ex:X a sh:NodeShape ; sh:targetNode ex:x ;
              sh:expression [ sparql:coalesce ( [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Y ) ] ) ] false ) ] .
            ex:Y a sh:NodeShape ; sh:node ex:X ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """
        val report = validate(provider, "ex:x ex:p 1 .", shapes)
        assertTrue(report.isValid, report.violations.toString())
        // A recursive expression over a data chain that ends is decided too, through coalesce.
        val chain = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:next ; sh:expression [ sparql:coalesce ( [ shnex:conformsToShape ( [ shnex:var "value" ] ex:S ) ] true ) ] ] .
        """
        val chained = validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:name 2 ; ex:next ex:c .", chain)
        assertEquals(1, chained.violations.size, chained.violations.toString())
        // With a data cycle the recursion is undefined: a failure, which coalesce no longer hides.
        assertThrows(ShaclValidationException::class.java) {
            validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:name 2 ; ex:next ex:a .", chain)
        }
    }
}
