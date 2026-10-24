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

/**
 * A compiled SHACL 1.2 node expression (Node Expressions §3–§6, and the SPARQL expressions of SHACL 1.2 SPARQL §6),
 * as parsed by [NodeExpressions.compile]. Children are node expressions themselves; evaluation is separate.
 */
internal sealed class NodeExpression {
    /**
     * Whether validation positions (`sh:values`, `sh:defaultValue`, `sh:targetNode`, function bodies) evaluate this
     * kind of expression yet: only [Constant] and [Select]. The others parse but stay reported as unsupported.
     */
    val supportedInValidation: Boolean get() = this is Constant || this is Select

    /** An IRI, literal or triple term; evaluates to itself (§3.1). */
    data class Constant(val term: RdfTerm) : NodeExpression()

    /** `[]`: a blank node that is the subject of no triple (§4.1.1). */
    data object Empty : NodeExpression()

    /** `shnex:var` (§4.1.2). */
    data class Var(val name: String) : NodeExpression()

    /** A non-empty SHACL list of constants used as an expression (§4.1.3). */
    data class ListExpr(val members: List<NodeExpression>) : NodeExpression()

    /** `shnex:pathValues` with optional `shnex:focusNode` (§4.1.4). */
    data class PathValues(val path: ShaclPath, val focusNode: NodeExpression?) : NodeExpression()

    /** `shnex:exists` (§4.1.5). */
    data class Exists(val e: NodeExpression) : NodeExpression()

    /** `shnex:if` / `shnex:then` / `shnex:else` (§4.1.6); at least one of the branches is present. */
    data class If(val cond: NodeExpression, val then: NodeExpression?, val otherwise: NodeExpression?) : NodeExpression()

    /** `shnex:distinct` (§4.2.1). */
    data class Distinct(val e: NodeExpression) : NodeExpression()

    /** `shnex:intersection` (§4.2.2). */
    data class Intersection(val members: List<NodeExpression>) : NodeExpression()

    /** `shnex:concat` (§4.2.3). */
    data class Concat(val members: List<NodeExpression>) : NodeExpression()

    /** `shnex:remove` / `shnex:nodes` (§4.2.4). */
    data class Remove(val remove: NodeExpression, val nodes: NodeExpression) : NodeExpression()

    /** `shnex:filterShape` / `shnex:nodes` (§4.2.5). */
    data class FilterShape(val shape: RdfResource, val nodes: NodeExpression) : NodeExpression()

    /** `shnex:limit` / `shnex:nodes` (§4.2.6); a limit beyond [Int.MAX_VALUE] is clamped to it. */
    data class Limit(val n: Int, val nodes: NodeExpression) : NodeExpression()

    /** `shnex:offset` / `shnex:nodes` (§4.2.7); an offset beyond [Int.MAX_VALUE] is clamped to it. */
    data class Offset(val n: Int, val nodes: NodeExpression) : NodeExpression()

    /** `shnex:orderBy` / `shnex:nodes` / `shnex:desc` (§4.2.8). */
    data class OrderBy(val key: NodeExpression, val nodes: NodeExpression, val desc: Boolean) : NodeExpression()

    /** `shnex:flatMap` with optional `shnex:nodes` (§4.3.1; null stands for the focus node). */
    data class FlatMap(val e: NodeExpression, val nodes: NodeExpression?) : NodeExpression()

    /** `shnex:findFirst` with optional `shnex:nodes` (§4.3.2; null stands for the focus node). */
    data class FindFirst(val shape: RdfResource, val nodes: NodeExpression?) : NodeExpression()

    /** `shnex:matchAll` with optional `shnex:nodes` (§4.3.3; null stands for the focus node). */
    data class MatchAll(val shape: RdfResource, val nodes: NodeExpression?) : NodeExpression()

    /** `shnex:count` (§4.4.1). */
    data class Count(val e: NodeExpression) : NodeExpression()

    /** `shnex:min` (§4.4.2). */
    data class Min(val e: NodeExpression) : NodeExpression()

    /** `shnex:max` (§4.4.3). */
    data class Max(val e: NodeExpression) : NodeExpression()

    /** `shnex:sum` (§4.4.4). */
    data class Sum(val e: NodeExpression) : NodeExpression()

    /** `shnex:instancesOf` (§4.5.1). */
    data class InstancesOf(val e: NodeExpression) : NodeExpression()

    /** `shnex:nodesMatching` (§4.5.2). */
    data class NodesMatching(val shape: RdfResource) : NodeExpression()

    /** `shnex:conformsToShape ( node shape )` (§4.5.3), a list parameter function. */
    data class ConformsToShape(val node: NodeExpression, val shape: NodeExpression) : NodeExpression()

    /** A SHACL SPARQL function expression `[ sparql:<name> ( args ) ]` (§5); [name] is the local name of the IRI. */
    data class SparqlCall(val name: String, val args: List<NodeExpression>) : NodeExpression()

    /** A custom named parameter expression (§6.1): [args] maps the `sh:path` of each given parameter to its expression. */
    data class CustomNamed(val function: Iri, val args: Map<Iri, NodeExpression>) : NodeExpression()

    /** A custom list parameter expression `[ fn ( args ) ]` (§6.2). */
    data class CustomList(val function: Iri, val args: List<NodeExpression>) : NodeExpression()

    /** `[ shnex:arg n ]` (§6.3): argument [index] of a custom list parameter function (clamped to the [Int] range). */
    data class Arg(val index: Int) : NodeExpression()

    /** `[ shnex:arg ex:param ]` (§6.3): the argument of a custom named parameter function whose `sh:path` is [parameter]. */
    data class NamedArg(val parameter: Iri) : NodeExpression()

    /**
     * A SPARQL node expression (SHACL 1.2 SPARQL §6): `sh:select`, or `sh:sparqlExpr` rewritten to
     * `SELECT (expr AS ?result) WHERE {}`. Only `this` is pre-bound (in a function body, `arg0`, `arg1`, ... as well).
     * The result nodes are the values of [variable].
     */
    class Select(val node: BlankNode, val template: SparqlQueryTemplate, val variable: String) : NodeExpression()

    /** The node expressions this one is built from (its arguments), in syntax order. */
    fun children(): List<NodeExpression> =
        when (this) {
            is Constant, Empty, is Var, is Arg, is NamedArg, is Select, is NodesMatching -> emptyList()
            is ListExpr -> members
            is PathValues -> listOfNotNull(focusNode)
            is Exists -> listOf(e)
            is If -> listOfNotNull(cond, then, otherwise)
            is Distinct -> listOf(e)
            is Intersection -> members
            is Concat -> members
            is Remove -> listOf(remove, nodes)
            is FilterShape -> listOf(nodes)
            is Limit -> listOf(nodes)
            is Offset -> listOf(nodes)
            is OrderBy -> listOf(key, nodes)
            is FlatMap -> listOfNotNull(e, nodes)
            is FindFirst -> listOfNotNull(nodes)
            is MatchAll -> listOfNotNull(nodes)
            is Count -> listOf(e)
            is Min -> listOf(e)
            is Max -> listOf(e)
            is Sum -> listOf(e)
            is InstancesOf -> listOf(e)
            is ConformsToShape -> listOf(node, shape)
            is SparqlCall -> args
            is CustomNamed -> args.values.toList()
            is CustomList -> args
        }
}

/** Compiles node expressions (SHACL 1.2 Node Expressions, and the SPARQL expressions of SHACL 1.2 SPARQL §6); evaluation is separate. */
internal object NodeExpressions {
    /** The variable `sh:sparqlExpr` results are projected as. */
    const val RESULT_VARIABLE = "result"

    private val PRE_BOUND = setOf("this")

    /**
     * Compiles [term] into a [NodeExpression] tree: constants, SPARQL expressions (`sh:select`, `sh:sparqlExpr`),
     * and every `shnex:`, `sparql:` and custom function of the shapes graph ([NodeExpressionParser]). Returns null only
     * for a blank node that is no node expression at all (such as a data node). Ill-formed expressions throw
     * [ShapeCompileException], pre-binding violations [SparqlPreBindingRestrictionException]. [preBound] are the
     * scope variables the restriction check of SPARQL expressions treats as potentially pre-bound (`this`, and the
     * `arg<i>` of a function).
     */
    fun compile(term: RdfTerm, index: ShapeGraphIndex, preBound: Set<String> = PRE_BOUND): NodeExpression? =
        NodeExpressionParser(index, preBound).parse(term)

    /**
     * The shapes that [expr] validates against, anywhere in its tree: the shapes of `shnex:filterShape`,
     * `shnex:findFirst`, `shnex:matchAll` and `shnex:nodesMatching`, and the constant IRI shape arguments of
     * `shnex:conformsToShape`. The shapes compiler compiles them, so that evaluation finds them (§3.4). A computed
     * `shnex:conformsToShape` shape cannot be known before evaluation: it is evaluated only when it is a shape of
     * the compiled shapes graph. Iterative and visiting a shared subexpression once, so any tree is walked in
     * linear time and constant stack.
     */
    fun shapeRefs(expr: NodeExpression): Set<RdfResource> {
        val out = LinkedHashSet<RdfResource>()
        walk(expr) { e ->
            when (e) {
                is NodeExpression.FilterShape -> out.add(e.shape)
                is NodeExpression.FindFirst -> out.add(e.shape)
                is NodeExpression.MatchAll -> out.add(e.shape)
                is NodeExpression.NodesMatching -> out.add(e.shape)
                is NodeExpression.ConformsToShape -> ((e.shape as? NodeExpression.Constant)?.term as? Iri)?.let { out.add(it) }
                else -> Unit
            }
        }
        return out
    }

    /**
     * Whether [expr] has a `shnex:conformsToShape` whose shape argument is computed (not a constant), so that the
     * shape it validates against is only known at evaluation time.
     */
    fun computesShapes(expr: NodeExpression): Boolean {
        var found = false
        walk(expr) { e -> if (e is NodeExpression.ConformsToShape && e.shape !is NodeExpression.Constant) found = true }
        return found
    }

    /** Visits every node of [expr] once, in syntax order, iteratively (constant stack, shared subtrees once). */
    private inline fun walk(expr: NodeExpression, visit: (NodeExpression) -> Unit) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NodeExpression, Boolean>())
        val pending = ArrayDeque<NodeExpression>()
        pending.add(expr)
        while (pending.isNotEmpty()) {
            val e = pending.removeLast()
            if (!seen.add(e)) continue
            visit(e)
            // Reversed, so that children are visited in syntax order.
            e.children().asReversed().forEach { pending.addLast(it) }
        }
    }

    /**
     * The expression of [term] at a validation position when validation evaluates it
     * ([NodeExpression.supportedInValidation]), else null so that the position stays reported as unsupported.
     * Kinds validation does not evaluate are not parsed at all: an ill-formed one is reported as unsupported as
     * well, not as a compile error, and costs no preparation budget.
     */
    fun compileForValidation(term: RdfTerm, index: ShapeGraphIndex, preBound: Set<String> = PRE_BOUND): NodeExpression? =
        compileSparqlOrConstant(term, index, preBound)?.takeIf { it.supportedInValidation }

    /**
     * Compiles [term] when it is a constant or a SPARQL expression, or returns null for any other blank node.
     * Ill-formed SPARQL expressions throw [ShapeCompileException], pre-binding violations
     * [SparqlPreBindingRestrictionException].
     */
    fun compileSparqlOrConstant(term: RdfTerm, index: ShapeGraphIndex, preBound: Set<String>): NodeExpression? {
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
     * one value (SHACL 1.2 Core §3.3), and only on a predicate [path]. Null when absent or not an expression
     * validation evaluates ([compileForValidation]).
     */
    fun compileParameter(shape: RdfResource, predicate: Iri, path: ShaclPath, index: ShapeGraphIndex): NodeExpression? {
        val values = index.objects(shape, predicate)
        if (values.isEmpty()) return null
        val name = predicate.value.substringAfter(SHACL.namespace)
        if (values.size > 1) throw ShapeCompileException("Property shape $shape has more than one sh:$name")
        if (path !is ShaclPath.Predicate) {
            throw ShapeCompileException("Property shape $shape has sh:$name, which needs a predicate path as sh:path")
        }
        return compileForValidation(values.single(), index)
    }

    private fun singleString(node: RdfResource, predicate: Iri, index: ShapeGraphIndex): String {
        val text = index.objects(node, predicate).singleOrNull() as? Literal
        if (text == null || text is LangString || text.datatype != XSD.string) {
            throw ShapeCompileException("Node expression $node must have exactly one xsd:string ${predicate.value.substringAfter(SHACL.namespace).let { "sh:$it" }}")
        }
        return text.lexical
    }
}
