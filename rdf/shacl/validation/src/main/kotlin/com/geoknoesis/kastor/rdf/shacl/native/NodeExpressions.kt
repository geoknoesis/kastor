package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

/** A compiled SHACL 1.2 node expression: a constant, or a SPARQL `SELECT` whose [Select.variable] column holds the result nodes. */
internal sealed class NodeExpression {
    /** An IRI, literal or triple term; evaluates to itself. */
    data class Constant(val term: RdfTerm) : NodeExpression()

    /**
     * A SPARQL node expression (SHACL 1.2 SPARQL §6): `sh:select`, or `sh:sparqlExpr` rewritten to
     * `SELECT (expr AS ?result) WHERE {}`. Only `this` is pre-bound (in a function body, `arg0`, `arg1`, ... as well).
     * The result nodes are the values of [variable].
     */
    class Select(val node: BlankNode, val template: SparqlQueryTemplate, val variable: String) : NodeExpression()
}

/** Compiles the SPARQL-based node expressions of SHACL 1.2 SPARQL §6; evaluation is separate. */
internal object NodeExpressions {
    /** The variable `sh:sparqlExpr` results are projected as. */
    const val RESULT_VARIABLE = "result"

    private val PRE_BOUND = setOf("this")

    /**
     * Compiles [term], or returns null when it is not a supported expression: a blank node with neither `sh:select`
     * nor `sh:sparqlExpr` (a `shnex:` or other expression). Ill-formed SPARQL expressions throw
     * [ShapeCompileException], pre-binding violations [SparqlPreBindingRestrictionException]. [preBound] are the
     * scope variables the restriction check treats as potentially pre-bound (`this`, and the `arg<i>` of a function).
     */
    fun compile(term: RdfTerm, index: ShapeGraphIndex, preBound: Set<String> = PRE_BOUND): NodeExpression? {
        index.tick("node expression compilation")
        if (term is Iri && (index.objects(term, SHACL.select).isNotEmpty() || index.objects(term, ShaclSparqlTerms.sparqlExpr).isNotEmpty())) {
            throw ShapeCompileException("SPARQL node expression $term must be a blank node")
        }
        if (term is Iri || term is Literal || term is TripleTerm) return NodeExpression.Constant(term)
        val node = term as? BlankNode ?: return null
        val types = index.objects(node, RDF.type)
        val hasSelect = index.objects(node, SHACL.select).isNotEmpty()
        val hasExpr = index.objects(node, ShaclSparqlTerms.sparqlExpr).isNotEmpty()
        if (SHACL.SelectExpression in types && !hasSelect) {
            throw ShapeCompileException("Node expression $node is a sh:SelectExpression and must have sh:select")
        }
        if (SHACL.SPARQLExprExpression in types && !hasExpr) {
            throw ShapeCompileException("Node expression $node is a sh:SPARQLExprExpression and must have sh:sparqlExpr")
        }
        if (hasSelect && hasExpr) throw ShapeCompileException("Node expression $node has both sh:select and sh:sparqlExpr")
        if (!hasSelect && !hasExpr) return null

        val text = singleString(node, if (hasSelect) SHACL.select else ShaclSparqlTerms.sparqlExpr, index)
        if (index.objects(node, SHACL.prefixes).size > 1) throw ShapeCompileException("Node expression $node has more than one sh:prefixes")
        val body = if (hasSelect) text else "SELECT ($text AS ?$RESULT_VARIABLE) WHERE {}"
        val template = SparqlQueryTemplate.compileRestricted(
            SparqlPrefixes.prologue(SparqlPrefixes.collect(node, index)), body, preBound, "Node expression $node",
        )
        val variable = if (hasSelect) {
            val projected = SparqlQueryTemplate.projectedVariables(body)
            if (projected == null || projected.size != 1) {
                throw ShapeCompileException("sh:select of node expression $node must project exactly one variable (not *)")
            }
            projected.single()
        } else {
            RESULT_VARIABLE
        }
        return NodeExpression.Select(node, template, variable)
    }

    /**
     * The expression of the optional `sh:values` / `sh:defaultValue` [predicate] of property shape [shape]: at most
     * one value (SHACL 1.2 Core §3.3), and only on a predicate [path]. Null when absent or not a supported expression.
     */
    fun compileParameter(shape: RdfResource, predicate: Iri, path: ShaclPath, index: ShapeGraphIndex): NodeExpression? {
        val values = index.objects(shape, predicate)
        if (values.isEmpty()) return null
        val name = predicate.value.substringAfter(SHACL.namespace)
        if (values.size > 1) throw ShapeCompileException("Property shape $shape has more than one sh:$name")
        if (path !is ShaclPath.Predicate) {
            throw ShapeCompileException("Property shape $shape has sh:$name, which needs a predicate path as sh:path")
        }
        return compile(values.single(), index)
    }

    private fun singleString(node: RdfResource, predicate: Iri, index: ShapeGraphIndex): String {
        val text = index.objects(node, predicate).singleOrNull() as? Literal
        if (text == null || text is LangString || text.datatype != XSD.string) {
            throw ShapeCompileException("Node expression $node must have exactly one xsd:string ${predicate.value.substringAfter(SHACL.namespace).let { "sh:$it" }}")
        }
        return text.lexical
    }
}
