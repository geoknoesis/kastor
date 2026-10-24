package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.SparqlLexer
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate
import com.geoknoesis.kastor.rdf.shacl.native.SparqlToken
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL 1.2 Node Expressions §8 (Dynamic SHACL) for the parameters of SPARQL-based constraint components: a blank-node
 * value of a parameter whose declaration rules out blank nodes (SHACL 1.2 SPARQL §4.2.1) is a node expression,
 * evaluated per focus node and pre-bound like a constant value.
 */
class DynamicSparqlComponentTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config) { repository(provider) }.validate(g(data), g(shapes))

    /** The shapes graph validated against itself, as the W3C cases do. */
    private fun validateSelf(provider: String, ttl: String) = g(ttl).let { NativeShaclValidator(ValidationConfig.default()) { repository(provider) }.validate(it, it) }

    private fun unsupported(error: Throwable): Set<UnsupportedShaclFeature> =
        generateSequence(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first().features

    /** The SPARQL-based constraints of the property shape ex:P. */
    private fun components(shapes: String, config: ValidationConfig = ValidationConfig.default()): List<PropertyConstraint.SparqlComponent> =
        ShapesCompiler.compile(g(shapes).getTriples(), config).referencedPropertyShapes.getValue(ex("P")).constraints
            .map { it.unwrapped() }.filterIsInstance<PropertyConstraint.SparqlComponent>()

    private val d = "$"

    /** [s] as a Turtle string literal. */
    private fun ttl(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // --- per focus node --------------------------------------------------------------------------------------------------

    private val maxAgeAsk = """
        ex:MaxAgeComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:maxAge ; sh:datatype xsd:integer ] ;
          sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (${d}value <= ${d}maxAge) }" ] .
    """

    private val strictOrNot = "[ shnex:if [ sparql:equals ( [ shnex:pathValues ex:kind ] ex:strict ) ] ; shnex:then 65 ; shnex:else 70 ]"

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an ASK component parameter is computed per focus node`(provider: String) {
        val shapes = maxAgeAsk + "ex:S a sh:NodeShape ; sh:targetClass ex:Person ; sh:property ex:P . ex:P sh:path ex:age ; ex:maxAge $strictOrNot ."
        val data = "ex:a a ex:Person ; ex:kind ex:strict ; ex:age 66 . ex:b a ex:Person ; ex:age 66 . ex:c a ex:Person ; ex:kind ex:strict ; ex:age 60 ."
        val v = validate(provider, data, shapes).violations.single()
        assertEquals(ex("a") to int(66), v.focusNode to v.value)
        assertEquals(ex("MaxAgeComponent").value, v.sourceConstraintComponent)
        val c = components(shapes).single()
        assertEquals(setOf("maxAge"), c.computed.keys)
        assertTrue(c.parameters.isEmpty(), c.parameters.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SELECT component parameter is computed and shown in the message`(provider: String) {
        val shapes = """
            ex:MaxAgeComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:maxAge ; sh:datatype xsd:integer ] ;
              sh:propertyValidator [ a sh:SPARQLSelectValidator ; sh:message "more than {${d}maxAge}" ;
                sh:select "SELECT ${d}this ?value WHERE { ${d}this ${d}PATH ?value FILTER (?value > ${d}maxAge) }" ] .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ; sh:property ex:P . ex:P sh:path ex:age ; ex:maxAge $strictOrNot .
        """
        val data = "ex:a a ex:Person ; ex:kind ex:strict ; ex:age 66 . ex:b a ex:Person ; ex:age 66 , 71 ."
        val report = validate(provider, data, shapes)
        assertEquals(setOf(ex("a") to int(66), ex("b") to int(71)), report.violations.map { it.focusNode to it.value }.toSet())
        assertEquals(2, report.violations.size)
        assertEquals(
            mapOf(ex("a") to "more than 65", ex("b") to "more than 70"),
            report.violations.associate { it.focusNode to it.resultMessages.single().lexical },
        )
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `the computed value is evaluated once per focus, not per value node`(provider: String) {
        // STRUUID gives a new string at every evaluation: one evaluation per focus node, so one tag per focus node.
        val shapes = """
            ex:TagComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:tag ; sh:datatype xsd:string ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:message "tag {${d}tag}" ; sh:ask "ASK { FILTER (STRLEN(${d}tag) = 0) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P . ex:P sh:path ex:v ; ex:tag [ sparql:struuid () ] .
        """
        val report = validate(provider, "ex:a ex:v 1 , 2 , 3 . ex:b ex:v 1 , 2 , 3 .", shapes)
        assertEquals(6, report.violations.size, report.violations.toString())
        val tags = report.violations.groupBy({ it.focusNode }, { it.resultMessages.single().lexical })
        assertEquals(setOf(ex("a"), ex("b")), tags.keys)
        assertTrue(tags.values.all { it.size == 3 && it.toSet().size == 1 }, tags.toString())
        assertEquals(2, tags.values.map { it.first() }.toSet().size, tags.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a failed component parameter inside sh not or sh node stays a failure`(provider: String) {
        // Checked only for conformance, the component's failed evaluation must not count as "does not conform".
        val inner = "[ sh:property [ sh:path ex:age ; ex:maxAge [ shnex:pathValues ex:none ] ] ]"
        for (wrapper in listOf("sh:not", "sh:node")) {
            val shapes = maxAgeAsk + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; $wrapper $inner ."
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:age 1 .", shapes) }, wrapper)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "no nodes" in message, "$wrapper: $message")
        }
    }

    // --- output rules ----------------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a component parameter with no output, several outputs, a blank node or the wrong datatype fails`(provider: String) {
        val limited = maxAgeAsk + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P . ex:P sh:path ex:age ; ex:maxAge [ shnex:pathValues ex:limit ] ."
        // Only the blank-node rule rejects a blank node here: sh:pattern rules blank nodes out, and is not an output check.
        val patterned = """
            ex:RefComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:ref ; sh:pattern "." ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (BOUND(${d}ref)) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P . ex:P sh:path ex:age ; ex:ref [ shnex:pathValues ex:limit ] .
        """
        val iriOnly = """
            ex:IriComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:ref ; sh:nodeKind sh:IRI ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (BOUND(${d}ref)) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P . ex:P sh:path ex:age ; ex:ref [ shnex:pathValues ex:limit ] .
        """
        val cases = listOf(
            Triple(limited, "ex:a ex:age 1 .", "no nodes"),
            Triple(limited, "ex:a ex:age 1 ; ex:limit 5 , 6 .", "2 nodes"),
            Triple(patterned, "ex:a ex:age 1 ; ex:limit [ ex:q 1 ] .", "blank node"),
            Triple(limited, "ex:a ex:age 1 ; ex:limit \"5\" .", "datatype"),
            Triple(iriOnly, "ex:a ex:age 1 ; ex:limit \"5\" .", "node kind"),
        )
        for ((shapes, data, problem) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, data, shapes) }, data)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && problem in message && "http://example.com/ns#" in message, message)
        }
        // The same expressions with a good value validate.
        assertEquals(listOf(ex("a")), validate(provider, "ex:a ex:age 9 ; ex:limit 5 .", limited).violations.map { it.focusNode })
        assertTrue(validate(provider, "ex:a ex:age 1 ; ex:limit \"5\" .", patterned).isValid)
        assertTrue(validate(provider, "ex:a ex:age 1 ; ex:limit ex:x .", iriOnly).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a computed triple term needs allowTripleTermsInShapeParameters and is then a provider binding`(provider: String) {
        // The ASK holds when the value node has the computed triple term as its ex:q: unbound, $tt would match anything.
        val shapes = """
            ex:TripleComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:tt ; sh:nodeKind sh:TripleTerm ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { ?value <http://example.com/ns#q> ${d}tt }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P . ex:P sh:path ex:v ; ex:tt [ shnex:pathValues ex:t ] .
        """
        val data = "ex:a ex:t <<( ex:s ex:p ex:o )>> ; ex:v ex:b , ex:c . ex:b ex:q <<( ex:s ex:p ex:o )>> . ex:c ex:q ex:x ."
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, data, shapes) }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "triple term" in message && "allowTripleTermsInShapeParameters" in message, message)
        val allowed = validate(provider, data, shapes, ValidationConfig(allowTripleTermsInShapeParameters = true))
        assertEquals(listOf(ex("a") to ex("c")), allowed.violations.map { it.focusNode to it.value })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a computed IRI that SPARQL cannot write is a validation failure`(provider: String) {
        // Kastor's Iri refuses what renderIri refuses, so such an IRI fails while the expression is evaluated; either
        // way the run fails with a ShaclValidationException, never a provider's IllegalArgumentException.
        val shapes = """
            ex:IriComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:ref ; sh:nodeKind sh:IRI ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (BOUND(${d}ref)) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P .
            ex:P sh:path ex:v ; ex:ref [ sparql:iri ( [ sparql:concat ( "http://example.com/a b{c" ) ] ) ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:v 1 .", shapes) }
        assertTrue("sparql:iri" in error.message.orEmpty(), error.toString())
    }

    // --- recognition -----------------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a parameter declared with only sh class keeps a blank-node constant`(provider: String) {
        // Review Focus #4: the shapes graph is the data graph, and _:b is a data node (an ex:C), never an expression.
        val ttl = """
            ex:RefComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:ref ; sh:class ex:C ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (isBlank(${d}ref) && ?value != \"bad\") }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P .
            ex:P sh:path ex:v ; ex:ref _:b .
            _:b a ex:C .
            ex:a ex:v "ok" , "bad" .
        """
        val c = components(ttl).single()
        assertTrue(c.computed.isEmpty(), c.computed.toString())
        assertTrue(c.parameters.getValue("ref") is BlankNode, c.parameters.toString())
        val v = validateSelf(provider, ttl).violations.single()
        assertEquals(ex("a") to "bad", v.focusNode to (v.value as Literal).lexical)
    }

    private fun declaration(decl: String) = """
        ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:x ; $decl ] ;
          sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (?value != ${d}x) }" ] .
        ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P . ex:P sh:path ex:v ; ex:x [ shnex:pathValues ex:w ] .
        ex:R a rdfs:Class .
    """

    @Test
    fun `each blank-excluding declaration constraint makes the parameter dynamic`() {
        val rows = listOf(
            "sh:nodeKind sh:IRI", "sh:nodeKind sh:Literal", "sh:nodeKind sh:IRIOrLiteral", "sh:nodeKind sh:TripleTerm",
            "sh:nodeKind ( sh:IRI sh:Literal )", "sh:datatype xsd:integer", "sh:datatype ( xsd:integer xsd:string )",
            "sh:in ( 1 2 )", "sh:in ()", "sh:pattern \"a\"", "sh:minLength 1", "sh:maxLength 3", "sh:minInclusive 1",
            "sh:maxInclusive 10", "sh:minExclusive 1", "sh:maxExclusive 10", "sh:languageIn ( \"en\" )", "sh:rootClass ex:R",
            "sh:rootClass ( ex:R )", "sh:class ex:K ; sh:datatype xsd:string",
        )
        for (row in rows) {
            val c = components(declaration(row)).single()
            assertEquals(setOf("x"), c.computed.keys, row)
            assertFalse("x" in c.parameters, row)
            assertTrue(c.parameterTriples.any { it.first == ex("x") && it.second is BlankNode }, "$row: ${c.parameterTriples}")
        }
    }

    @Test
    fun `a nodeKind list with sh BlankNode does not make the parameter dynamic`() {
        val rows = listOf(
            "sh:nodeKind ( sh:IRI sh:BlankNode )", "sh:nodeKind sh:BlankNodeOrIRI", "sh:nodeKind sh:BlankNodeOrLiteral",
            "sh:nodeKind sh:BlankNode", "sh:class ex:K", "sh:hasValue 1", "sh:singleLine true", "sh:in ( 1 [] )",
            "sh:name \"x\"", "sh:datatype [ shnex:pathValues ex:dt ]", "sh:minLength [ shnex:pathValues ex:n ]",
            // An empty constant list at sh:nodeKind or sh:datatype makes no constraint, so it rules nothing out.
            "sh:nodeKind ()", "sh:datatype ()",
        )
        for (row in rows) {
            val c = components(declaration(row)).single()
            assertTrue(c.computed.isEmpty(), row)
            assertTrue(c.parameters.getValue("x") is BlankNode, row)
        }
    }

    @Test
    fun `a blank node that is no node expression at a blank-excluding parameter is a compile error`() {
        val shapes = declaration("sh:datatype xsd:integer").replace("[ shnex:pathValues ex:w ]", "[ ex:foo 1 ]")
        for (config in listOf(ValidationConfig.default(), ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING))) {
            val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", shapes, config) }
            val compile = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ShapeCompileException>().firstOrNull()
            assertTrue(compile != null, error.toString())
            val message = compile!!.message.orEmpty()
            assertTrue("neither a constant nor a node expression" in message && "rules out blank nodes" in message, message)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.none { it is UnsupportedShaclFeatureException }, error.toString())
        }
    }

    @Test
    fun `a call of an undeclared function at a blank-excluding parameter is an unsupported feature`() {
        val shapes = declaration("sh:datatype xsd:integer").replace("[ shnex:pathValues ex:w ]", "[ ex:undeclared ( 1 ) ]")
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported(error))
        assertTrue(ex("x").value in error.message.orEmpty(), error.message)
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:v 1 .", shapes, ignore)
        assertTrue(
            report.warnings.any { "Unsupported SHACL feature ignored" in it.message && it.message.endsWith("(the constraints that use it are left out)") },
            report.warnings.toString(),
        )
        assertTrue(report.violations.isEmpty(), report.violations.toString())
        assertTrue(components(shapes, ignore).isEmpty())
    }

    @Test
    fun `an unsupported feature inside a shape reached only through a component parameter expression is detected`() {
        val shapes = declaration("sh:datatype xsd:integer").replace(
            "[ shnex:pathValues ex:w ]", "[ shnex:count [ shnex:filterShape ex:Hidden ; shnex:nodes [ shnex:pathValues ex:v ] ] ]",
        ) + "\nex:Hidden sh:target [ a ex:CustomTarget ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.CUSTOM_TARGET), unsupported(error))
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:v 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "sh:target" in it.message }, report.warnings.toString())
    }

    @Test
    fun `a reifier deactivates a computed component parameter triple`() {
        val component = maxAgeAsk + "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P ."
        fun shapes(annotation: String) = "$component ex:P sh:path ex:age ; ex:maxAge _:e $annotation . _:e shnex:if true ; shnex:then 5 ; shnex:else 5 ."
        val data = "ex:a ex:age 9 ; ex:skip true . ex:b ex:age 9 ."
        assertEquals(listOf(ex("a"), ex("b")), validate("jena", data, shapes("")).violations.map { it.focusNode }.sortedBy { it.toString() })
        assertTrue(validate("jena", data, shapes("{| sh:deactivated true |}")).violations.isEmpty())
        assertEquals(listOf(ex("b")), validate("jena", data, shapes("{| sh:deactivated [ shnex:pathValues ex:skip ] |}")).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a component parameter expression's shapes are compiled`(provider: String) {
        // ex:Big is reachable only from the expression at ex:maxAge.
        val shapes = maxAgeAsk + """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P .
            ex:P sh:path ex:age ; ex:maxAge [ shnex:count [ shnex:filterShape ex:Big ; shnex:nodes [ shnex:pathValues ex:part ] ] ] .
            ex:Big sh:property [ sh:path ex:size ; sh:minInclusive 10 ] .
        """
        // One big part each: the age must be at most 1.
        val data = "ex:a ex:age 1 ; ex:part ex:p1 , ex:p2 . ex:b ex:age 2 ; ex:part ex:p1 , ex:p2 . ex:p1 ex:size 20 . ex:p2 ex:size 1 ."
        val v = validate(provider, data, shapes).violations.single()
        assertEquals(ex("b") to int(2), v.focusNode to v.value)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a data cycle through a component parameter expression is a failure`(provider: String) {
        // ex:S counts the ex:p values that conform to ex:S itself: a non-monotone read of ex:S (R16).
        val shapes = maxAgeAsk + """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P .
            ex:P sh:path ex:p ; ex:maxAge [ shnex:count [ shnex:filterShape ex:S ; shnex:nodes [ shnex:pathValues ex:p ] ] ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p ex:a .", shapes) }
        assertTrue("undefined" in error.message.orEmpty(), error.toString())
    }

    // --- hostile values --------------------------------------------------------------------------------------------------

    /** Lexical forms that would change the validator query if they could leave the term they are written in. */
    private val hostile = listOf(
        "x\") || true || (\"",
        "} ; DROP ALL ; #",
        "a\\u0022b",
        "a\\\\u0022b",
        "line\nbreak",
        "x\\u0022) || (\\u0022a\\u0022 = \\u0022a",
        "x\\U00000022) || (\\U00000022a\\U00000022 = \\U00000022a",
        "x\\u0022)) SERVICE <http://127.0.0.1:1/x> { ?s ?p ?o } #",
        "x\\\\\\u0022) || (true",
        "\\u005C\\u0022 \\u0027 \\u003E \\u000A",
        "trailing \\",
        "plain \" and ' quotes",
    )

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `hostile computed values cannot change the validator query`(provider: String) {
        val component = """
            ex:ForbiddenComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:forbidden ; sh:datatype xsd:string ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:message "forbidden {${d}forbidden}" ;
                sh:ask "ASK { FILTER (STR(${d}value) != ${d}forbidden) }" ] .
        """
        val dynamic = component + "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:forbidden ; sh:property [ sh:path ex:v ; ex:forbidden [ shnex:pathValues ex:forbidden ] ] ."
        val data = hostile.mapIndexed { i, p -> "ex:a$i ex:forbidden ${ttl(p)} ; ex:v ${ttl(p)} , \"other\" ." }.joinToString("\n")
        val report = validate(provider, data, dynamic)
        val expected = hostile.mapIndexed { i, p -> ex("a$i") to p }.toSet()
        assertEquals(expected, report.violations.map { it.focusNode to (it.value as Literal).lexical }.toSet())
        assertEquals(hostile.size, report.violations.size)
        assertEquals(expected.map { it.first to "forbidden ${it.second}" }.toSet(), report.violations.map { it.focusNode to it.resultMessages.single().lexical }.toSet())
        // The oracle: the same validator with constant parameters that hold no special character.
        val constant = component + hostile.indices.joinToString("\n") { i ->
            "ex:S$i a sh:NodeShape ; sh:targetNode ex:a$i ; sh:property [ sh:path ex:v ; ex:forbidden \"plain$i\" ] ."
        }
        val oracleData = hostile.indices.joinToString("\n") { i -> "ex:a$i ex:v \"plain$i\" , \"other\" ." }
        val oracle = validate(provider, oracleData, constant)
        assertEquals(hostile.indices.map { ex("a$it") to "plain$it" }.toSet(), oracle.violations.map { it.focusNode to (it.value as Literal).lexical }.toSet())
        assertEquals(oracle.violations.size, report.violations.size)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `hostile computed language strings cannot change a SELECT validator`(provider: String) {
        val shapes = """
            ex:SameComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:same ; sh:nodeKind sh:Literal ] ;
              sh:propertyValidator [ a sh:SPARQLSelectValidator ; sh:message "same {${d}same}" ;
                sh:select '''SELECT ${d}this ?value WHERE { ${d}this ${d}PATH ?value .
                  FILTER (STR(?value) = STR(${d}same) && LANG(?value) = LANG(${d}same)) }''' ] .
            ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:same ; sh:property [ sh:path ex:v ; ex:same [ shnex:pathValues ex:same ] ] .
        """
        val data = hostile.mapIndexed { i, p -> "ex:a$i ex:same ${ttl(p)}@en ; ex:v ${ttl(p)}@en , ${ttl(p)}@de , \"other\"@en ." }.joinToString("\n")
        val report = validate(provider, data, shapes)
        assertEquals(hostile.mapIndexed { i, p -> ex("a$i") to LangString(p, "en") }.toSet(), report.violations.map { it.focusNode to it.value }.toSet())
        assertEquals(hostile.size, report.violations.size)
        assertEquals(hostile.mapIndexed { i, p -> ex("a$i") to "same $p" }.toSet(), report.violations.map { it.focusNode to it.resultMessages.single().lexical }.toSet())
    }

    /**
     * The significant tokens of [text], by kind, and the text of every token that is not a string: what the query
     * means apart from its string values.
     */
    private fun structure(text: String): List<String> =
        SparqlLexer.tokenize(text).filter { it.significant }.map { if (it.kind == SparqlToken.Kind.STRING) "STRING" else "${it.kind}:${it.text}" }

    @Test
    fun `a language tag written into a plain slot ends before the template text that follows`() {
        val template = SparqlQueryTemplate.compile("SELECT ?x WHERE { BIND (${d}p-1 AS ?x) }", setOf("p"))
        val (text, bindings) = template.bind(mapOf("p" to LangString("a", "en")))
        assertTrue(bindings.isEmpty())
        assertTrue("\"a\"@en -1" in text, text)
        // Without the space, the tag would read en-1.
        assertTrue("@en-1" !in text, text)
    }

    @Test
    fun `a computed value is written into the query as one string term under every reading`() {
        // The text a hostile value gives has the structure a plain value gives: in the text as written (Jena), and
        // after a Java-style (RDF4J) or a naive codepoint pre-pass.
        val template = SparqlQueryTemplate.compile("SELECT ?this WHERE { FILTER (STR(${d}value) != ${d}forbidden) }", setOf("this", "value", "forbidden"))
        val base = mapOf<String, RdfTerm>("this" to ex("a"), "value" to TypedLiteral("v", XSD.string))
        fun bound(term: RdfTerm) = template.bind(base + ("forbidden" to term))
        for (p in hostile) {
            for ((term, plainTerm) in listOf(
                TypedLiteral(p, XSD.string) to TypedLiteral("plain", XSD.string),
                LangString(p, "en") to LangString("plain", "en"),
            )) {
                val (text, bindings) = bound(term)
                assertTrue(bindings.isEmpty(), "$term is written into the text")
                val expected = structure(bound(plainTerm).first)
                for (view in listOf(text, SparqlQueryTemplate.decodeCodepointEscapes(text, strict = true), SparqlQueryTemplate.decodeCodepointEscapes(text, strict = false))) {
                    assertEquals(expected, structure(view), "$term: $view")
                }
            }
        }
    }
}
