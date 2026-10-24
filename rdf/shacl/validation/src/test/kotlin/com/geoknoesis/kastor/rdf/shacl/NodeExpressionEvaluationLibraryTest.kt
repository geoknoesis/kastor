package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)
    private fun str(s: String) = TypedLiteral(s, XSD.string)

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

    // --- custom functions (§6) --------------------------------------------------------------------------------------

    /** §6.1 Example 25. */
    private val average = """
        ex:AverageExpression a sh:NamedParameterExpressionFunction ;
          rdfs:subClassOf sh:NamedParameterExpression ;
          sh:parameter ex:AverageExpression-average ;
          sh:bodyExpression [ sparql:divide ( [ shnex:sum [ shnex:arg ex:average ] ] [ shnex:count [ shnex:arg ex:average ] ] ) ] .
        ex:AverageExpression-average a sh:Parameter ; sh:path ex:average ; sh:keyParameter true .
    """

    /** A named parameter function that outputs the nodes of its key argument twice, then those of `ex:extra`. */
    private val twice = """
        ex:Twice a sh:NamedParameterExpressionFunction ;
          sh:parameter [ sh:path ex:twice ; sh:keyParameter true ] , [ sh:path ex:extra ] ;
          sh:bodyExpression [ shnex:concat ( [ shnex:arg ex:twice ] [ shnex:arg ex:twice ] [ shnex:arg ex:extra ] ) ] .
    """

    /** §6.2 Example 27. */
    private val spacedConcat = """
        ex:spacedConcat a sh:ListParameterExpressionFunction ;
          rdfs:subClassOf sh:ListParameterExpression ;
          sh:bodyExpression [ sparql:concat ( [ shnex:arg 0 ] " " [ shnex:arg 1 ] ) ] ;
          sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg1 ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `custom named and list parameter functions`(provider: String) {
        // §6.1 Example 26: the argument is a node expression, evaluated where the body uses shnex:arg.
        val company = "ex:holder ex:expr [ ex:average [ shnex:pathValues ( ex:employee ex:income ) ] ] . " +
            "ex:C ex:employee ex:e1 , ex:e2 . ex:e1 ex:income 10 . ex:e2 ex:income 20 . $average"
        val avg = nodes(eval(company, focus = ex("C"), provider = provider)).single() as Literal
        assertEquals(XSD.decimal, avg.datatype)
        assertEquals(0, BigDecimal("15").compareTo(BigDecimal(avg.lexical)), avg.lexical)
        // The argument keeps its list of output nodes; a parameter that is not given has no output nodes.
        assertEquals(listOf(int(1), int(2), int(1), int(2)), nodes(eval("ex:holder ex:expr [ ex:twice ( 1 2 ) ] . $twice")))
        assertEquals(listOf(int(1), int(1), int(3)), nodes(eval("ex:holder ex:expr [ ex:twice 1 ; ex:extra 3 ] . $twice")))
        // §6.3: named arguments are evaluated with an empty scope, for the focus node of the arg expression.
        val x = mapOf("x" to int(1))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("""ex:holder ex:expr [ ex:twice [ shnex:var "x" ] ] . $twice""", variables = x)))
        assertEquals(listOf(ex("a"), ex("a")), nodes(eval("""ex:holder ex:expr [ ex:twice [ shnex:var "focusNode" ] ] . $twice""", focus = ex("a"))))
        // A failing argument fails the call where the body evaluates it (§3.3).
        assertFailure(eval("ex:holder ex:expr [ ex:twice $failing ] . $twice"), "more than one")

        // §6.2 Example 28: arguments are evaluated in the scope of the call, each to at most one node.
        fun concat(args: String, variables: Map<String, RdfTerm> = emptyMap()) =
            eval(
                """ex:holder ex:expr [ ex:spacedConcat ( $args ) ] . ex:p ex:firstName "John" ; ex:lastName "Doe" . $spacedConcat""",
                focus = ex("p"), variables = variables, provider = provider,
            )
        assertEquals(listOf(str("John Doe")), nodes(concat("[ shnex:pathValues ex:firstName ] [ shnex:pathValues ex:lastName ]")))
        assertEquals(listOf(str("a Doe")), nodes(concat("""[ shnex:var "x" ] [ shnex:pathValues ex:lastName ]""", mapOf("x" to str("a")))))
        // §3.2.2: more than one node is a failure, none is no output, and a failure takes precedence over none.
        assertFailure(concat("""( "a" "b" ) "c""""), "more than one")
        assertEquals(emptyList<RdfTerm>(), nodes(concat("""[] "c"""")))
        assertFailure(concat("""[] ( "a" "b" )"""), "more than one")
        // The body sees the arguments only, not the scope variables of the call.
        val leak = """ex:leak a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:concat ( [ shnex:var "x" ] 1 ) ] ."""
        assertEquals(listOf(int(1)), nodes(eval("ex:holder ex:expr [ ex:leak () ] . $leak", variables = mapOf("x" to int(9)))))
        // §6.2: a body with more than one output node is a failure.
        val pair = "ex:pair a sh:ListParameterExpressionFunction ; sh:bodyExpression ( 1 2 ) ."
        assertFailure(eval("ex:holder ex:expr [ ex:pair () ] . $pair"), "more than one")
    }

    @Test
    fun `arg expressions`() {
        // §6.3: outside a custom function, the scope has no arguments.
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ shnex:arg 0 ] .")))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ shnex:arg ex:p ] .")))
        // List arguments are nodes, returned as constants; an index without an argument has no output nodes.
        val pick = """
            ex:first a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:arg 0 ] .
            ex:third a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:arg 2 ] .
            ex:named a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:arg ex:p ] .
        """
        assertEquals(listOf(ex("a")), nodes(eval("ex:holder ex:expr [ ex:first ( ex:a 2 ) ] . $pick")))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ ex:third ( ex:a 2 ) ] . $pick")))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ ex:named ( ex:a ) ] . $pick")))
        // A positional arg in a named parameter function has no output nodes either.
        val named = "ex:N a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:n ; sh:keyParameter true ] ; sh:bodyExpression [ shnex:arg 0 ] ."
        assertEquals(emptyList<RdfTerm>(), nodes(eval("ex:holder ex:expr [ ex:n 1 ] . $named")))
        // Arguments stay in scope in the nested expressions of the body.
        val nested = "ex:rep a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:count [ shnex:flatMap [ shnex:arg 0 ] ; shnex:nodes ( 1 2 ) ] ] ."
        assertEquals(listOf(int(2)), nodes(eval("ex:holder ex:expr [ ex:rep ( 5 ) ] . $nested")))
    }

    @Test
    fun `recursive custom function fails at the depth limit`() {
        val loops = """
            ex:loop a sh:ListParameterExpressionFunction ; sh:bodyExpression [ ex:loop () ] .
            ex:Rec a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:rec ; sh:keyParameter true ] ;
              sh:bodyExpression [ ex:rec [ shnex:arg ex:rec ] ] .
        """
        assertFailure(eval("ex:holder ex:expr [ ex:loop () ] . $loops"), "nested more than")
        assertFailure(eval("ex:holder ex:expr [ ex:rec 1 ] . $loops"), "nested more than")
    }

    @Test
    fun `an ill-formed custom function is an error only where it is used`() {
        val bad = """
            ex:Bad a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:bad ; sh:keyParameter true ] , [ sh:name "no path" ] ;
              sh:bodyExpression 1 .
            ex:NoBody a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:noBody ; sh:keyParameter true ] .
            ex:twoBodies a sh:ListParameterExpressionFunction ; sh:bodyExpression 1 , 2 .
            ex:badBody a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
            ex:usesBad a sh:ListParameterExpressionFunction ; sh:bodyExpression [ ex:badBody () ] .
        """
        assertEquals(listOf(int(2)), nodes(eval("ex:holder ex:expr [ shnex:count ( 1 2 ) ] . $bad")))
        for (use in listOf("[ ex:bad 1 ]", "[ ex:noBody 1 ]", "[ ex:twoBodies () ]", "[ ex:badBody () ]", "[ ex:usesBad () ]", "[ shnex:count [ ex:bad 1 ] ]")) {
            assertThrows(ShapeCompileException::class.java, { eval("ex:holder ex:expr $use . $bad") }, use)
        }
    }

    @Test
    fun `shapes used only in custom function bodies are compiled`() {
        val functions = """
            ex:ints a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:count [ shnex:filterShape ex:Int ; shnex:nodes ( 1 2.5 3 ) ] ] .
            ex:Wrap a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:wrap ; sh:keyParameter true ] ;
              sh:bodyExpression [ ex:ints () ] .
            ex:Int sh:datatype xsd:integer .
        """
        assertEquals(listOf(int(2)), nodes(eval("ex:holder ex:expr [ ex:wrap 1 ] . $functions")))
    }

    @Test
    fun `if compares the condition by term equality with true`() {
        // R5: "1"^^xsd:boolean has the value true but is not the term "true"^^xsd:boolean, so the else branch is taken.
        assertEquals(listOf(int(2)), nodes(eval("""ex:holder ex:expr [ shnex:if "1"^^xsd:boolean ; shnex:then 1 ; shnex:else 2 ] .""")))
        assertEquals(listOf(int(1)), nodes(eval("""ex:holder ex:expr [ shnex:if "true"^^xsd:boolean ; shnex:then 1 ; shnex:else 2 ] .""")))
    }

    @Test
    fun `depth cap allows exactly 128 levels`() {
        fun nested(wrappers: Int): NodeExpression {
            var e: NodeExpression = NodeExpression.Constant(int(1))
            repeat(wrappers) { e = NodeExpression.Distinct(e) }
            return e
        }
        val graph = Rdf.graph { }
        val validator = NativeShaclValidator(ValidationConfig.default())
        // 127 wrappers and the constant: 128 levels.
        assertEquals(listOf(int(1)), nodes(validator.evaluateNodeExpression(graph, nested(127), null, emptyMap())))
        assertFailure(validator.evaluateNodeExpression(graph, nested(128), null, emptyMap()), "128")
    }

    @Test
    fun `list expressions are capped`() {
        val config = ValidationConfig.default().copy(maxPathValueNodes = 2)
        assertFailure(eval("ex:holder ex:expr ( 1 2 3 ) .", config = config), "maxPathValueNodes=2")
        assertEquals(listOf(int(1), int(2)), nodes(eval("ex:holder ex:expr ( 1 2 ) .", config = config)))
    }

    // Shape-based functions (§4.2.5, §4.3.2, §4.3.3, §4.5.2, §4.5.3).

    @Test
    fun `filterShape keeps conforming nodes in order`() {
        // §4.2.5, Example 14: the male children.
        val data = """
            ex:holder ex:expr [
                shnex:nodes ( ex:C3 ex:C1 ex:C2 ex:C3 ) ;
                shnex:filterShape [ sh:property [ sh:path ex:gender ; sh:hasValue "male" ] ] ] .
            ex:C1 ex:gender "male" . ex:C2 ex:gender "female" . ex:C3 ex:gender "male" .
        """
        assertEquals(listOf(ex("C3"), ex("C1"), ex("C3")), nodes(eval(data)))
        val byPath = """
            ex:holder ex:expr [
                shnex:nodes [ shnex:pathValues ex:child ] ;
                shnex:filterShape [ sh:property [ sh:path ex:gender ; sh:hasValue "male" ] ] ] .
            ex:Mom ex:child ex:C1, ex:C2 .
            ex:C1 ex:gender "male" . ex:C2 ex:gender "female" .
        """
        assertEquals(listOf(ex("C1")), nodes(eval(byPath, focus = ex("Mom"))))
    }

    @Test
    fun `findFirst returns the first conforming node`() {
        // §4.3.2, Example 18: the first senior employee.
        val data = """
            ex:holder ex:expr [ shnex:nodes ( ex:E1 ex:E2 ex:E3 ) ; shnex:findFirst ex:SeniorEmployeeShape ] .
            ex:SeniorEmployeeShape a sh:NodeShape ;
                sh:property [ sh:path ex:yearsOfService ; sh:datatype xsd:integer ; sh:minInclusive 5 ] .
            ex:E1 ex:yearsOfService 3 . ex:E2 ex:yearsOfService 7 . ex:E3 ex:yearsOfService 10 .
        """
        assertEquals(listOf(ex("E2")), nodes(eval(data)))
        // Without shnex:nodes, N is the focus node, or nothing without one.
        val atFocus = "ex:holder ex:expr [ shnex:findFirst [ sh:minInclusive 3 ] ] ."
        assertEquals(listOf(int(4)), nodes(eval(atFocus, focus = int(4))))
        assertEquals(emptyList<RdfTerm>(), nodes(eval(atFocus, focus = int(2))))
        assertEquals(emptyList<RdfTerm>(), nodes(eval(atFocus)))
    }

    @Test
    fun `matchAll is true when every node conforms`() {
        // §4.3.3, Example 19: are all employees active?
        val shapes = """
            ex:ActiveEmployeeShape a sh:NodeShape ; sh:property [ sh:path ex:isActive ; sh:hasValue true ] .
            ex:Co1 ex:employee ex:E1, ex:E2 . ex:Co2 ex:employee ex:E1, ex:E3 .
            ex:E1 ex:isActive true . ex:E2 ex:isActive true . ex:E3 ex:isActive false .
        """
        val expr = "ex:holder ex:expr [ shnex:nodes [ shnex:pathValues ex:employee ] ; shnex:matchAll ex:ActiveEmployeeShape ] ."
        assertEquals(listOf(TrueLiteral), nodes(eval(expr + shapes, focus = ex("Co1"))))
        assertEquals(listOf(FalseLiteral), nodes(eval(expr + shapes, focus = ex("Co2"))))
        // Every node of the empty list conforms.
        assertEquals(listOf(TrueLiteral), nodes(eval("ex:holder ex:expr [ shnex:nodes [] ; shnex:matchAll ex:ActiveEmployeeShape ] .$shapes")))
    }

    @Test
    fun `nodesMatching returns the matching nodes of the focus graph in SPARQL order`() {
        // §4.5.2, Example 23, with a smaller minimum.
        val data = """
            ex:holder ex:expr [ shnex:nodesMatching [ sh:class ex:Company ; sh:property [ sh:path ex:employee ; sh:minCount 2 ] ] ] .
            ex:Zeta a ex:Company ; ex:employee ex:E1, ex:E2 .
            ex:Alpha a ex:Company ; ex:employee ex:E1, ex:E2, ex:E3 .
            ex:Small a ex:Company ; ex:employee ex:E1 .
            ex:NotACompany ex:employee ex:E1, ex:E2 .
        """
        assertEquals(listOf(ex("Alpha"), ex("Zeta")), nodes(eval(data)))
        assertFailure(eval(data, config = ValidationConfig.default().copy(maxPathValueNodes = 1)), "maxPathValueNodes=1")
    }

    @Test
    fun `conformsToShape produces true or false, nothing for an empty argument`() {
        // §4.5.3, Example 24: has director.
        val shape = "ex:HasDirectorShape a sh:NodeShape ; sh:property [ sh:path ex:director ; sh:minCount 1 ] . ex:Co1 ex:director ex:Alice ."
        val data = """ex:holder ex:expr [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:HasDirectorShape ) ] . $shape"""
        assertEquals(listOf(TrueLiteral), nodes(eval(data, focus = ex("Co1"))))
        assertEquals(listOf(FalseLiteral), nodes(eval(data, focus = ex("Co2"))))
        // No focus node: the node argument has no value, so the function is not applied (§3.2.2).
        assertEquals(emptyList<RdfTerm>(), nodes(eval(data)))
        fun call(args: String, variables: Map<String, RdfTerm> = emptyMap()) =
            eval("ex:holder ex:expr [ shnex:conformsToShape ( $args ) ] . $shape", variables = variables)
        // The shape argument may be computed.
        assertEquals(listOf(TrueLiteral), nodes(call("""ex:Co1 [ shnex:var "s" ]""", mapOf("s" to ex("HasDirectorShape")))))
        assertEquals(emptyList<RdfTerm>(), nodes(call("""ex:Co1 [ shnex:var "s" ]""")))
        // More than one node in an argument is a failure.
        assertFailure(call("( ex:Co1 ex:Co2 ) ex:HasDirectorShape"), "node argument of shnex:conformsToShape produced more than one")
        assertFailure(call("ex:Co1 ( ex:HasDirectorShape ex:HasDirectorShape )"), "shape argument of shnex:conformsToShape produced more than one")
        // A failing argument ($failing: a path values expression with two focus nodes) takes precedence over an
        // argument without output nodes, whatever their order (§3.2.2).
        assertFailure(call("""[ shnex:var "unbound" ] $failing"""), "shnex:focusNode of a path values expression")
        assertFailure(call("""$failing [ shnex:var "unbound" ]"""), "shnex:focusNode of a path values expression")
        // The shape must be the IRI of a shape of the shapes graph.
        assertFailure(call("ex:Co1 1"), "IRI")
        assertFailure(call("""ex:Co1 [ shnex:var "s" ]""", mapOf("s" to ex("Unknown"))), "not a shape")
    }

    @Test
    fun `shape reachable only from an expression is compiled`() {
        // ex:S has no type and no target, and nothing else references it.
        val data = """
            ex:holder ex:expr [ shnex:filterShape ex:S ; shnex:nodes ( 1 2.5 3 ) ] .
            ex:S sh:datatype xsd:integer .
        """
        assertEquals(listOf(int(1), int(3)), nodes(eval(data)))
        // Nested in another expression, and the shapes it references in turn.
        val nested = """
            ex:holder ex:expr [ shnex:count [ shnex:filterShape ex:S ; shnex:nodes ( 1 2.5 3 ) ] ] .
            ex:S sh:node ex:T . ex:T sh:datatype xsd:integer .
        """
        assertEquals(listOf(int(2)), nodes(eval(nested)))
        // A constant shape argument of conformsToShape.
        assertEquals(listOf(FalseLiteral), nodes(eval("ex:holder ex:expr [ shnex:conformsToShape ( 2.5 ex:S ) ] . ex:S sh:datatype xsd:integer .")))
    }

    /**
     * ex:S: ex:g conforms (ex:p "good"), ex:b does not (no ex:p, no ex:loop), and ex:u is undecided (it has ex:loop,
     * so it conforms iff it does not).
     */
    private val mixedShape = """
        ex:S sh:or ( [ sh:property [ sh:path ex:p ; sh:hasValue "good" ] ]
                     [ sh:property [ sh:path ex:loop ; sh:minCount 1 ] ; sh:not ex:S ] ) .
        ex:g ex:p "good" . ex:u ex:loop true . ex:b ex:p "bad" .
    """

    @Test
    fun `matchAll checks every node`() {
        fun matchAll(nodes: String) = eval("ex:holder ex:expr [ shnex:matchAll ex:S ; shnex:nodes ( $nodes ) ] . $mixedShape")
        assertEquals(listOf(TrueLiteral), nodes(matchAll("ex:g ex:g")))
        assertEquals(listOf(FalseLiteral), nodes(matchAll("ex:g ex:b")))
        // R6: an undecided check anywhere is a failure, also after a node that does not conform.
        assertFailure(matchAll("ex:b ex:u"), "undefined")
        assertFailure(matchAll("ex:u ex:g"), "undefined")
    }

    @Test
    fun `findFirst stops at the first conforming node`() {
        fun findFirst(nodes: String) = eval("ex:holder ex:expr [ shnex:findFirst ex:S ; shnex:nodes ( $nodes ) ] . $mixedShape")
        // Nodes after the first conforming one are not checked.
        assertEquals(listOf(ex("g")), nodes(findFirst("ex:b ex:g ex:u")))
        // An undecided check before it is a failure.
        assertFailure(findFirst("ex:b ex:u ex:g"), "undefined")
    }

    @Test
    fun `a computed shape IRI may name any shape of the shapes graph`() {
        fun call(shape: Iri, shapes: String) =
            eval("""ex:holder ex:expr [ shnex:conformsToShape ( 2.5 [ shnex:var "s" ] ) ] . $shapes""", variables = mapOf("s" to shape))
        // R7: a deactivated shape that nothing references; every node conforms to it.
        assertEquals(listOf(TrueLiteral), nodes(call(ex("Off"), "ex:Off a sh:NodeShape ; sh:datatype xsd:integer ; sh:deactivated true .")))
        assertEquals(listOf(TrueLiteral), nodes(call(ex("Off"), "ex:Off sh:datatype xsd:integer ; sh:deactivated true .")))
        // An active shape that is neither declared nor referenced.
        assertEquals(listOf(FalseLiteral), nodes(call(ex("Int"), "ex:Int sh:datatype xsd:integer .")))
        assertEquals(listOf(TrueLiteral), nodes(call(ex("Dec"), "ex:Dec sh:datatype xsd:decimal .")))
        // An IRI that is no shape at all.
        assertFailure(call(ex("holder"), ""), "not a shape")
    }

    @Test
    fun `undecided conformance is a failure`() {
        // ex:S conforms iff it does not conform: the recursion has no defined answer.
        val shapes = "ex:S sh:not ex:S ."
        assertFailure(eval("ex:holder ex:expr [ shnex:filterShape ex:S ; shnex:nodes ( ex:a ) ] . $shapes"), "undefined")
        assertFailure(eval("ex:holder ex:expr [ shnex:conformsToShape ( ex:a ex:S ) ] . $shapes"), "undefined")
        assertFailure(eval("ex:holder ex:expr [ shnex:matchAll ex:S ; shnex:nodes ( ex:a ) ] . $shapes"), "undefined")
    }
}
