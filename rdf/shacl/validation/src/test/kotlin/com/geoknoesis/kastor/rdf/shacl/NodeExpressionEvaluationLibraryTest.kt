package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Evaluation of the SHACL 1.2 Node Expressions library (§4) that needs neither shapes nor SPARQL functions. */
class NodeExpressionEvaluationLibraryTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    /** Evaluates the object of `ex:holder ex:expr` in [ttl], which is also the focus graph and the shapes graph. */
    private fun eval(
        ttl: String,
        focus: RdfTerm? = null,
        variables: Map<String, RdfTerm> = emptyMap(),
        provider: String = "jena",
        config: ValidationConfig = ValidationConfig.default(),
    ): NodeExprOutcome {
        val graph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
        val expr = graph.find(ex("holder"), ex("expr"), null).single().obj
        return NativeShaclValidator(config) { repository(provider) }.evaluateNodeExpression(graph, expr, focus, variables)
    }

    private fun nodes(outcome: NodeExprOutcome): List<RdfTerm> {
        assertTrue(outcome is NodeExprOutcome.Nodes, "expected output nodes, got $outcome")
        return (outcome as NodeExprOutcome.Nodes).nodes
    }

    private fun assertFailure(outcome: NodeExprOutcome, reasonContains: String = "") {
        assertTrue(outcome is NodeExprOutcome.Failure, "expected a failure, got $outcome")
        assertTrue((outcome as NodeExprOutcome.Failure).reason.contains(reasonContains), "unexpected reason: ${outcome.reason}")
    }

    /** A path values expression whose focus node expression has two output nodes: an evaluation failure (§4.1.4). */
    private val failing = "[ shnex:pathValues ex:p ; shnex:focusNode ( 1 2 ) ]"

    @Test
    fun `distinct keeps 01 and 1 apart`() {
        val out = nodes(eval("""ex:holder ex:expr [ shnex:distinct ( "01"^^xsd:integer "1"^^xsd:integer "01"^^xsd:integer ) ] ."""))
        assertEquals(listOf(TypedLiteral("01", XSD.integer), TypedLiteral("1", XSD.integer)), out)
    }

    @Test
    fun `intersection and remove compare by term equality`() {
        val inter = nodes(eval("""ex:holder ex:expr [ shnex:intersection ( ( "01"^^xsd:integer 1 2 2 ) ( 2 1 ) ) ] ."""))
        assertEquals(setOf(int(1), int(2)), inter.toSet())
        assertEquals(2, inter.size)
        val removed = nodes(eval("""ex:holder ex:expr [ shnex:remove ( 1 ) ; shnex:nodes ( "01"^^xsd:integer 1 3 1 ) ] ."""))
        assertEquals(listOf(TypedLiteral("01", XSD.integer), int(3)), removed)
    }

    @Test
    fun `if is lazy`() {
        assertFailure(eval("ex:holder ex:expr $failing ."), "more than one")
        assertEquals(listOf(int(1)), nodes(eval("ex:holder ex:expr [ shnex:if true ; shnex:then 1 ; shnex:else $failing ] .")))
        assertEquals(listOf(int(2)), nodes(eval("ex:holder ex:expr [ shnex:if false ; shnex:then $failing ; shnex:else 2 ] .")))
        // A failing condition fails the if expression (§3.3).
        assertFailure(eval("ex:holder ex:expr [ shnex:if $failing ; shnex:then 1 ] ."))
    }

    @Test
    fun `nested failures propagate`() {
        for (outer in listOf(
            "[ shnex:count $failing ]",
            "[ shnex:concat ( ( 1 ) $failing ) ]",
            "[ shnex:limit 1 ; shnex:nodes $failing ]",
            "[ shnex:flatMap $failing ; shnex:nodes ( 1 ) ]",
            "[ shnex:orderBy $failing ; shnex:nodes ( 1 ) ]",
            "[ shnex:exists $failing ]",
        )) {
            assertFailure(eval("ex:holder ex:expr $outer ."), "more than one")
        }
    }

    @Test
    fun `list cap produces a failure`() {
        val config = ValidationConfig.default().copy(maxPathValueNodes = 2)
        assertFailure(eval("ex:holder ex:expr [ shnex:concat ( ( 1 2 ) ( 3 ) ) ] .", config = config), "maxPathValueNodes=2")
        assertEquals(listOf(int(1), int(2)), nodes(eval("ex:holder ex:expr [ shnex:concat ( ( 1 ) ( 2 ) ) ] .", config = config)))
        // Path values beyond the cap are a failure as well.
        val paths = "ex:holder ex:expr [ shnex:pathValues ex:p ; shnex:focusNode ex:a ] . ex:a ex:p 1, 2, 3 ."
        assertFailure(eval(paths, config = config), "maxPathValueNodes=2")
    }

    @Test
    fun `nesting beyond the depth cap produces a failure`() {
        var deep: NodeExpression = NodeExpression.Constant(int(1))
        repeat(200) { deep = NodeExpression.Distinct(deep) }
        val graph = Rdf.graph { }
        val validator = NativeShaclValidator(ValidationConfig.default())
        assertFailure(validator.evaluateNodeExpression(graph, deep, null, emptyMap()), "128")
        var shallow: NodeExpression = NodeExpression.Constant(int(1))
        repeat(100) { shallow = NodeExpression.Distinct(shallow) }
        assertEquals(listOf(int(1)), nodes(validator.evaluateNodeExpression(graph, shallow, null, emptyMap())))
    }

    @Test
    fun `var focusNode and scope`() {
        val expr = { name: String -> """ex:holder ex:expr [ shnex:var "$name" ] .""" }
        // 1. "focusNode" is the focus node, even when the scope has a variable of that name.
        assertEquals(listOf(ex("f")), nodes(eval(expr("focusNode"), focus = ex("f"), variables = mapOf("focusNode" to ex("other")))))
        // 2. A scope variable.
        assertEquals(listOf(int(4)), nodes(eval(expr("x"), focus = ex("f"), variables = mapOf("x" to int(4)))))
        // 3. Otherwise nothing.
        assertEquals(emptyList<RdfTerm>(), nodes(eval(expr("y"), focus = ex("f"), variables = mapOf("x" to int(4)))))
        assertEquals(emptyList<RdfTerm>(), nodes(eval(expr("focusNode"))))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `min max sum follow SPARQL`(provider: String) {
        fun e(body: String) = eval("ex:holder ex:expr $body .", provider = provider)
        assertEquals(listOf(int(1)), nodes(e("[ shnex:min ( 3 1 2 ) ]")))
        assertEquals(listOf(int(3)), nodes(e("[ shnex:max ( 3 1 2 ) ]")))
        assertEquals(emptyList<RdfTerm>(), nodes(e("[ shnex:min [] ]")))
        // SPARQL order: blank nodes < IRIs < literals.
        assertEquals(listOf(ex("a")), nodes(e("[ shnex:min ( 2 ex:a ) ]")))
        assertEquals(listOf(TypedLiteral("3.5", XSD.decimal)), nodes(e("[ shnex:sum ( 1 2.5 ) ]")))
        assertEquals(listOf(int(0)), nodes(e("[ shnex:sum [] ]")))
        assertFailure(e("""[ shnex:sum ( "a" ) ]"""))
        assertFailure(e("[ shnex:sum ( 1 ex:a ) ]"))
    }

    @Test
    fun `count and exists`() {
        assertEquals(listOf(int(3)), nodes(eval("ex:holder ex:expr [ shnex:count ( 4 3 3 ) ] .")))
        assertEquals(listOf(TypedLiteral("true", XSD.boolean)), nodes(eval("ex:holder ex:expr [ shnex:exists ( 1 ) ] .")))
        assertEquals(listOf(TypedLiteral("false", XSD.boolean)), nodes(eval("ex:holder ex:expr [ shnex:exists [] ] .")))
    }

    @Test
    fun `orderBy desc and keys`() {
        // §4.2.8 with the example of §4.2.6: the two oldest children.
        val data = """
            ex:holder ex:expr [
                shnex:limit 2 ;
                shnex:nodes [ shnex:nodes [ shnex:pathValues ex:child ] ; shnex:orderBy [ shnex:pathValues ex:dateOfBirth ] ] ] .
            ex:holder ex:desc [
                shnex:desc true ;
                shnex:nodes [ shnex:pathValues ex:child ] ; shnex:orderBy [ shnex:pathValues ex:dateOfBirth ] ] .
            ex:Mom ex:child ex:C1, ex:C2, ex:C3, ex:C4 .
            ex:C1 ex:dateOfBirth "2001-05-01"^^xsd:date .
            ex:C2 ex:dateOfBirth "1999-01-01"^^xsd:date .
            ex:C3 ex:dateOfBirth "2003-01-01"^^xsd:date .
        """
        // ex:C4 has no key: unbound keys are smallest.
        assertEquals(listOf(ex("C4"), ex("C2")), nodes(eval(data, focus = ex("Mom"))))
        val graph = Rdf.parse(prefixes + "\n" + data, RdfFormat.TURTLE)
        val desc = graph.find(ex("holder"), ex("desc"), null).single().obj
        val out = nodes(NativeShaclValidator(ValidationConfig.default()).evaluateNodeExpression(graph, desc, ex("Mom"), emptyMap()))
        assertEquals(listOf(ex("C3"), ex("C1"), ex("C2"), ex("C4")), out)
        // Mixed numeric datatypes compare by value; the key is the first output node.
        val numbers = nodes(eval("ex:holder ex:expr [ shnex:nodes ( 10 2.5 3 ) ; shnex:orderBy [ shnex:var \"focusNode\" ] ] ."))
        assertEquals(listOf(TypedLiteral("2.5", XSD.decimal), int(3), int(10)), numbers)
    }

    @Test
    fun `offset limit flatMap and path values`() {
        assertEquals(listOf(int(2), int(3)), nodes(eval("ex:holder ex:expr [ shnex:offset 1 ; shnex:nodes ( 1 2 3 ) ] .")))
        assertEquals(listOf(int(1)), nodes(eval("ex:holder ex:expr [ shnex:limit 1 ; shnex:nodes ( 1 2 3 ) ] .")))
        val flat = """
            ex:holder ex:expr [ shnex:flatMap [ shnex:pathValues ex:p ] ; shnex:nodes ( ex:a ex:b ex:a ) ] .
            ex:a ex:p 1 . ex:b ex:p 2 .
        """
        assertEquals(listOf(int(1), int(2), int(1)), nodes(eval(flat)))
        // Without shnex:nodes, flatMap and pathValues start at the focus node.
        assertEquals(listOf(int(1)), nodes(eval("ex:holder ex:expr [ shnex:flatMap [ shnex:pathValues ex:p ] ] . ex:a ex:p 1 .", focus = ex("a"))))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ shnex:pathValues ex:p ; shnex:focusNode [] ] .")))
    }

    @Test
    fun `instancesOf includes subclasses and rejects non-IRI types`() {
        val data = """
            ex:holder ex:expr [ shnex:instancesOf ex:Super ] .
            ex:Sub rdfs:subClassOf ex:Super . ex:x a ex:Sub . ex:y a ex:Super .
        """
        assertEquals(setOf(ex("x"), ex("y")), nodes(eval(data)).toSet())
        assertFailure(eval("ex:holder ex:expr [ shnex:instancesOf ( ex:Super 1 ) ] ."), "IRI")
    }

    @Test
    fun `functions of later tasks are reported unsupported`() {
        for (body in listOf(
            "[ shnex:filterShape ex:S ; shnex:nodes ( 1 ) ]",
            "[ shnex:nodesMatching ex:S ]",
            "[ shnex:conformsToShape ( 1 ex:S ) ]",
            "[ <http://www.w3.org/ns/sparql#abs> -1 ]",
        )) {
            val outcome = eval("ex:holder ex:expr $body . ex:S a sh:NodeShape .")
            assertTrue(outcome is NodeExprOutcome.Failure && outcome.reason.startsWith("unsupported:"), "$body: $outcome")
        }
    }
}
