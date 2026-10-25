package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The compile closure ([ShapeClosure]) and the recursion analysis ([ShapeDependencies.recursiveComponents]) are both
 * derived from [ShapeReads]. Each case below is one construct through which shape ex:A reads ex:B, while ex:B reads
 * ex:A back through `sh:node`. ex:B is no top-level shape (no type, no target), so only the closure compiles it, and
 * it must; the analysis must put ex:A and ex:B in one recursive component, unless the construct only belongs to the
 * targets of ex:A (compiled, but read by no conformance check).
 *
 * ex:U reads ex:A back as well, and nothing reads ex:U: it is recursive only when a construct of ex:A may name any
 * shape (a computed shape reference whose outputs are not known before evaluation, fail-closed). A computed reference
 * whose possible outputs are constants, and a SPARQL query that calls ex:f but not ex:h (whose body reads ex:U), do
 * not make every shape a dependency.
 */
class ShapeDependencyAgreementTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private val d = "$"
    private val readsB = """[ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:B ) ]"""
    private val maybeB = "[ shnex:if [ shnex:exists [ shnex:pathValues ex:p ] ] ; shnex:then ex:B ; shnex:else ex:NoShape ]"
    private val fromData = "[ shnex:pathValues ex:shape ]"
    private val function = """
        ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
          sh:parameter [ sh:path shnex:arg0 ] ; sh:bodyExpression [ shnex:conformsToShape ( [ shnex:arg 0 ] ex:B ) ] .
        ex:h a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
          sh:parameter [ sh:path shnex:arg0 ] ; sh:bodyExpression [ shnex:conformsToShape ( [ shnex:arg 0 ] ex:U ) ] .
    """
    private val maxAge = """
        ex:MaxAgeComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:maxAge ; sh:datatype xsd:integer ] ;
          sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (${d}value <= ${d}maxAge) }" ] .
    """

    private enum class Edge { RECURSIVE, TARGETS_ONLY, ANY_SHAPE }

    /** A construct of ex:A that reads ex:B, the declarations it needs, and what the analysis must make of it. */
    private class Case(val name: String, val a: String, val edge: Edge = Edge.RECURSIVE, val extra: String = "")

    private val cases = listOf(
        Case("sh:node", "sh:node ex:B"),
        Case("constant sh:nodeByExpression", "sh:nodeByExpression ex:B"),
        Case("sh:and", "sh:and ( ex:B )"),
        Case("sh:or", "sh:or ( ex:B )"),
        Case("sh:xone", "sh:xone ( ex:B )"),
        Case("sh:not", "sh:not ex:B"),
        Case("sh:property / sh:node", "sh:property [ sh:path ex:p ; sh:node ex:B ]"),
        Case("nested sh:property", "sh:property [ sh:path ex:p ; sh:property [ sh:path ex:q ; sh:node ex:B ] ]"),
        Case("property logical constraint", "sh:property [ sh:path ex:p ; sh:or ( ex:B ) ]"),
        Case("sh:qualifiedValueShape", "sh:property [ sh:path ex:p ; sh:qualifiedValueShape ex:B ; sh:qualifiedMinCount 1 ]"),
        Case(
            "sh:qualifiedValueShapesDisjoint sibling",
            "sh:property [ sh:path ex:p ; sh:qualifiedValueShape ex:Q ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] , " +
                "[ sh:path ex:q ; sh:qualifiedValueShape ex:B ; sh:qualifiedMaxCount 5 ]",
            extra = "ex:Q a sh:NodeShape .",
        ),
        Case("sh:memberShape", "sh:property [ sh:path ex:p ; sh:memberShape ex:B ]"),
        Case("sh:someValue", "sh:property [ sh:path ex:p ; sh:someValue ex:B ]"),
        Case("sh:reifierShape", "sh:property [ sh:path ex:p ; sh:reifierShape ex:B ]"),
        Case("sh:expression", "sh:expression $readsB"),
        Case("sh:values", "sh:property [ sh:path ex:p ; sh:values [ shnex:filterShape ex:B ; shnex:nodes ( ex:x ) ] ]"),
        Case("sh:defaultValue", "sh:property [ sh:path ex:p ; sh:defaultValue [ shnex:filterShape ex:B ; shnex:nodes ( ex:x ) ] ]"),
        Case("shape sh:deactivated", "sh:deactivated $readsB"),
        Case("property shape sh:deactivated", "sh:property [ sh:path ex:p ; sh:deactivated $readsB ]"),
        Case("reifier of a constraint", "sh:class ex:C {| sh:deactivated $readsB |}"),
        Case("reifier of sh:property", "sh:property ex:P {| sh:deactivated $readsB |}", extra = "ex:P sh:path ex:p ."),
        Case("reifier of a logical constraint", "sh:or ( ex:T ) {| sh:deactivated $readsB |}", extra = "ex:T a sh:NodeShape ."),
        Case("reifier of sh:closed", "sh:closed true {| sh:deactivated $readsB |}"),
        Case("sh:sparql sh:deactivated", "sh:sparql [ sh:select 'SELECT ${d}this WHERE { }' ; sh:deactivated $readsB ]"),
        Case("computed sh:closed", "sh:closed $readsB"),
        Case("computed sh:uniqueValuesFor", "sh:uniqueValuesFor [ shnex:if $readsB ; shnex:then ex:p ; shnex:else ex:q ]"),
        Case("computed core parameter", "sh:property [ sh:path ex:p ; sh:minCount [ shnex:if $readsB ; shnex:then 1 ; shnex:else 0 ] ]"),
        Case("computed component parameter", "sh:property [ sh:path ex:p ; ex:maxAge [ shnex:if $readsB ; shnex:then 1 ; shnex:else 2 ] ]", extra = maxAge),
        Case("computed sh:nodeByExpression with known outputs", "sh:nodeByExpression $maybeB"),
        Case("computed shnex:conformsToShape with known outputs", """sh:expression [ shnex:conformsToShape ( [ shnex:var "focusNode" ] $maybeB ) ]"""),
        Case("computed sh:nodeByExpression from the data", "sh:nodeByExpression $fromData", Edge.ANY_SHAPE),
        Case("computed shnex:conformsToShape from the data", """sh:expression [ shnex:conformsToShape ( [ shnex:var "focusNode" ] $fromData ) ]""", Edge.ANY_SHAPE),
        Case("SPARQL constraint calling a function", "sh:sparql [ sh:select 'SELECT ${d}this WHERE { FILTER (!<http://example.com/ns#f>(${d}this)) }' ]", extra = function),
        Case("SPARQL expression calling a function", "sh:expression [ sh:sparqlExpr '<http://example.com/ns#f>(${d}this)' ]", extra = function),
        Case(
            "SPARQL function calling a function",
            "sh:sparql [ sh:select 'SELECT ${d}this WHERE { FILTER (!<http://example.com/ns#g>(${d}this)) }' ]",
            extra = function + """
                ex:g a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
                  sh:parameter [ sh:path shnex:arg0 ] ; sh:bodyExpression [ sh:sparqlExpr '<http://example.com/ns#f>(${d}arg0)' ] .
            """,
        ),
        Case("SPARQL query with a relative call (every function)", "sh:sparql [ sh:select 'SELECT ${d}this WHERE { FILTER (!<f>(${d}this)) }' ]", Edge.ANY_SHAPE, function),
        Case("sh:targetWhere", "sh:targetWhere ex:B", Edge.TARGETS_ONLY),
        Case("sh:targetNode expression", "sh:targetNode [ shnex:filterShape ex:B ; shnex:nodes ( ex:x ) ]", Edge.TARGETS_ONLY),
    )

    private fun compile(case: Case): CompiledShapeGraph {
        val ttl = """
            $prefixes
            ${case.extra}
            ex:A a sh:NodeShape ; ${case.a} .
            ex:B sh:node ex:A .
            ex:U a sh:NodeShape ; sh:node ex:A .
        """
        return ShapesCompiler.compile(Rdf.parse(ttl, RdfFormat.TURTLE).getTriples().toList(), ValidationConfig.default())
    }

    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    @Test
    fun `the closure and the recursion analysis agree on every construct`() {
        val wrong = ArrayList<String>()
        for (case in cases) {
            val graph = try {
                compile(case)
            } catch (e: Exception) {
                wrong.add("${case.name}: $e")
                continue
            }
            val compiled = graph.referencedNodeShapes.keys + graph.referencedPropertyShapes.keys
            val components = graph.recursiveComponents
            val a = components[ex("A")]
            fun check(what: String, ok: Boolean) {
                if (!ok) wrong.add("${case.name}: $what (components: $components)")
            }
            check("ex:B is compiled", ex("B") in compiled)
            when (case.edge) {
                Edge.TARGETS_ONLY -> check("ex:A is not recursive", a == null)
                else -> check("ex:A and ex:B are one component", a != null && components[ex("B")] == a)
            }
            check(
                if (case.edge == Edge.ANY_SHAPE) "ex:U is a dependency" else "ex:U is no dependency",
                (case.edge == Edge.ANY_SHAPE) == (a != null && components[ex("U")] == a),
            )
            // Every edge of the analysis is a compiled shape or a candidate that did not compile: what the analysis
            // follows, the closure compiled.
            val resolver = ShapeDependencyResolver(ExpressionShapes(graph.index), graph.functions, ValidationBudget.NONE)
            val graphEdges = ShapeDependencies.dependencyGraph(graph.referencedNodeShapes, graph.referencedPropertyShapes, resolver)
            for ((shape, successors) in graphEdges) {
                val missing = successors.filter { it !in compiled && it !in graph.candidateErrors }
                check("the successors $missing of $shape are compiled", missing.isEmpty())
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a SPARQL query that calls no function does not depend on the function bodies`() {
        // ex:C runs SPARQL, but not ex:f, whose body reads ex:B; ex:B reads ex:C. No cycle.
        val ttl = """
            $prefixes
            $function
            ex:C a sh:NodeShape ; sh:sparql [ sh:select 'SELECT ${d}this WHERE { FILTER (false) }' ] .
            ex:B a sh:NodeShape ; sh:node ex:C .
            ex:D a sh:NodeShape ; sh:sparql [ sh:select 'SELECT ${d}this WHERE { FILTER (!<http://example.com/ns#f>(${d}this)) }' ] ; sh:node ex:C .
        """
        val graph = ShapesCompiler.compile(Rdf.parse(ttl, RdfFormat.TURTLE).getTriples().toList(), ValidationConfig.default())
        assertEquals(emptyMap<Any, Int>(), graph.recursiveComponents)
        // Once it calls ex:f, it does.
        val calling = ttl.replace("FILTER (false)", "FILTER (!<http://example.com/ns#f>(${d}this))")
        val recursive = ShapesCompiler.compile(Rdf.parse(calling, RdfFormat.TURTLE).getTriples().toList(), ValidationConfig.default())
        assertEquals(setOf(ex("B"), ex("C")), recursive.recursiveComponents.keys)
    }
}
