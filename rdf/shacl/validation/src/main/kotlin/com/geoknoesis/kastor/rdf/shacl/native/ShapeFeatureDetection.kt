package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.isParameterPredicate
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.shExpression
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.isDeactivated

/** Recognised SHACL constructs the native engine cannot evaluate ([detectUnsupportedFeatures]). */
internal object ShapeFeatureDetection {

    private val shTarget = Iri(SHACL.namespace + "target")

    /** True for SHACL 1.2 SPARQL node expressions (`sh:SelectExpression`, `sh:SPARQLExprExpression`, `sh:select`, `sh:sparqlExpr`). */
    internal fun isNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        val node = term as? RdfResource ?: return false
        val types = index.objects(node, RDF.type)
        return SHACL.SelectExpression in types || SHACL.SPARQLExprExpression in types ||
            listOf(SHACL.selectExpression, SHACL.exprExpression, SHACL.select, ShaclSparqlTerms.sparqlExpr).any { index.objects(node, it).isNotEmpty() }
    }

    /**
     * Blank node `sh:targetNode` values. In SHACL 1.2 the values of `sh:targetNode` are node expressions: an IRI or
     * a literal is a constant, a blank node is a computed expression. A blank node of the shapes graph can only
     * denote a node of the data graph when both graphs share it (the same graph validated against itself, or shapes
     * discovered from the data). The rule is therefore:
     *
     * 1. **Compile time (this function).** The blank node is a node expression, whatever the data graph is, when its
     *    own triples use vocabulary that has **no reading as data**: a SPARQL expression (`sh:select`,
     *    `sh:sparqlExpr`, the SHACL SPARQL expression types), the SHACL node-expression (`shnex:`) or SPARQL
     *    function (`sparql:`) namespaces as predicate or type, or a single call `[ ex:fn ( … ) ]` of a function
     *    **declared** in the shapes graph (`ex:fn` typed `sh:Function` / `sh:SPARQLFunction` or described with SHACL
     *    function vocabulary such as `sh:parameter`). It is evaluated when it compiles ([NodeExpressions.compile]),
     *    else reported per [ValidationConfig.unsupportedFeatures] (W3C `sparql/targets/targetNode-select-001`
     *    validates such a shapes graph against itself).
     * 2. **Validation time.** Any other blank node — a plain one, an RDF list cell, one that carries `sh:`
     *    predicates or a SHACL type (a shape used as data), a call-shaped one `[ ex:fn ( … ) ]` with `ex:fn`
     *    undeclared — is an **ordinary target when it is a node of the data graph**, regardless of its predicates.
     *    When it is not, it can never be a focus node and the shape would silently go unvalidated: it is then an
     *    unsupported node expression, handled per [ValidationConfig.unsupportedFeatures] like the ones of rule 1
     *    (failure, or a report warning under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING]).
     */
    internal fun isTargetNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        if (term !is BlankNode) return false
        val predicates = index.predicates(term)
        if (predicates.isEmpty()) return false
        if (isNodeExpression(term, index)) return true
        fun expressionVocabulary(t: RdfTerm) = t is Iri && (t.value.startsWith(ShaclSparqlTerms.SHNEX_NAMESPACE) || t.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE))
        if (predicates.any { expressionVocabulary(it) }) return true
        if (index.objects(term, RDF.type).any { expressionVocabulary(it) }) return true
        val call = callPredicate(term, index) ?: return false
        return isDeclaredFunction(call, index)
    }

    /** `fn` when [node] has the syntax of a function call `[ fn ( … ) ]`: a single triple whose object is an RDF list. */
    internal fun callPredicate(node: BlankNode, index: ShapeGraphIndex): Iri? {
        val call = index.predicates(node).singleOrNull() ?: return null
        val argument = index.objects(node, call).singleOrNull() ?: return null
        return call.takeIf { argument == RDF.nil || (argument is BlankNode && index.objects(argument, RDF.first).isNotEmpty()) }
    }

    /** A function IRI declared in the shapes graph: typed with a SHACL class or described with SHACL predicates. */
    private fun isDeclaredFunction(function: RdfTerm, index: ShapeGraphIndex): Boolean {
        val node = function as? RdfResource ?: return false
        if (index.objects(node, RDF.type).any { it is Iri && it.value.startsWith(SHACL.namespace) }) return true
        return index.predicates(node).any { it.value.startsWith(SHACL.namespace) }
    }

    /**
     * True when [term] compiles as a node expression ([NodeExpressions.compile]): a constant, a SPARQL expression, or
     * any expression of the library. An ill-formed one counts as supported: the compiler reports it with its own error
     * where the expression is used (a deactivated shape never compiles its expressions). A blank node that is no node
     * expression at all (such as a call of an undeclared function) is not.
     */
    private fun isSupportedExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean =
        try {
            NodeExpressions.compile(term, index) != null
        } catch (_: ShapeCompileException) {
            true
        }

    /**
     * True when [term], the value of a constraint parameter Dynamic SHACL may compute (§8), is a call `[ fn ( … ) ]`
     * of a function the engine does not know: an expression it cannot evaluate, so an unsupported feature like one at
     * any other expression position. Any other blank node that is no node expression is not one at all, and makes the
     * shapes graph ill-formed ([DynamicParameters.value]); a constant SHACL list at the list-taking [predicate], even a
     * malformed one, is no expression either ([DynamicParameters.isConstantList]).
     */
    internal fun isUnsupportedParameterExpression(predicate: Iri, term: RdfTerm, index: ShapeGraphIndex): Boolean =
        term is BlankNode && !DynamicParameters.isConstantList(predicate, term, index) &&
            callPredicate(term, index) != null && !isSupportedExpression(term, index)

    private val shapeReferencePredicates: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`not`, SHACL.qualifiedValueShape, SHACL.someValue, SHACL.memberShape,
            SHACL.shape, SHACL.reifierShape, SHACL.targetWhere,
        )
    }

    /** Shapes reachable from some roots ([shapeReachableNodes]), and which candidates their computed shape references need. */
    private class Reach(val shapes: Set<RdfResource>, val needsCandidates: Boolean)

    /**
     * [roots] and every node reachable from them through shape-valued parameters (a constant `sh:nodeByExpression`
     * IRI included) and through the shapes of the node expressions at `sh:values`, `sh:defaultValue`, `sh:targetNode`,
     * `sh:expression`, `sh:nodeByExpression` and the computed constraint parameters ([NodeExpressions.shapeRefs]).
     * The candidates of computed shape references are not followed: [Reach.needsCandidates] tells whether there are any.
     */
    private fun shapeReachableNodes(
        index: ShapeGraphIndex,
        roots: Collection<RdfResource>,
        exprShapes: ExpressionShapes,
        budget: ValidationBudget,
    ): Reach {
        val out = LinkedHashSet<RdfResource>()
        val pending = ArrayDeque<RdfResource>()
        var needsCandidates = false
        fun visit(term: RdfTerm) {
            if (term is RdfResource && out.add(term)) pending.add(term)
        }
        /** The shapes of the node expression [term]; constants and SPARQL expressions reference none. */
        fun expression(term: RdfTerm, computedShape: Boolean) {
            if (term !is BlankNode) return
            if (computedShape) needsCandidates = true
            if (isNodeExpression(term, index)) return
            // An ill-formed expression references none: the compiler reports it.
            val expr = try {
                NodeExpressions.compile(term, index)
            } catch (_: ShapeCompileException) {
                null
            } ?: return
            exprShapes.strict(expr).forEach(::visit)
            if (exprShapes.computes(expr)) needsCandidates = true
        }
        roots.forEach(::visit)
        while (pending.isNotEmpty()) {
            budget.tick("feature detection")
            val shape = pending.removeFirst()
            for (p in shapeReferencePredicates) index.objects(shape, p).forEach(::visit)
            for (p in listOf(SHACL.`and`, SHACL.`or`, SHACL.xone)) {
                // Malformed lists are reported by the compiler itself.
                index.objects(shape, p).forEach { head -> runCatching { index.parseRdfList(head) }.getOrDefault(emptyList()).forEach(::visit) }
            }
            for (p in expressionPositions) index.objects(shape, p).forEach { expression(it, computedShape = false) }
            // So are the parameters of SPARQL-based constraint components whose declaration rules out blank nodes.
            for (p in index.sparqlComponents.dynamicParameterPaths()) index.objects(shape, p).forEach { expression(it, computedShape = false) }
            // The sh:deactivated of a SPARQL-based constraint is a node expression too.
            for (c in index.objects(shape, SHACL.sparql)) {
                if (c is RdfResource) index.objects(c, SHACL.deactivated).forEach { expression(it, computedShape = false) }
            }
            // So is the sh:deactivated of a reifier of one of the shape's parameter triples (Core §3.1.6). On any other
            // triple it has no effect, so the shapes it reads are not validated against.
            for (p in index.predicates(shape)) {
                if (!isParameterPredicate(p, index)) continue
                for (o in index.objects(shape, p)) {
                    budget.tick("feature detection")
                    for (r in index.reifiersForClaim(RdfTriple(shape, p, o))) index.objects(r, SHACL.deactivated).forEach { expression(it, computedShape = false) }
                }
            }
            // An IRI is a constant shape reference; any other value computes its shapes.
            index.objects(shape, SHACL.nodeByExpression).forEach { if (it is Iri) visit(it) else expression(it, computedShape = true) }
        }
        return Reach(out, needsCandidates)
    }

    /** Shape predicates whose values may be node expressions, including the parameters Dynamic SHACL computes (§8). */
    private val expressionPositions: List<Iri> by lazy {
        listOf(ShaclSparqlTerms.values, ShaclSparqlTerms.defaultValue, SHACL.targetNode, shExpression, SHACL.deactivated) +
            DynamicParameters.predicates
    }

    /**
     * Unsupported constructs: [strict] of the shapes the run validates against, by description, and [candidateOnly]:
     * those of shapes that only a computed shape reference can name, by shape (R8).
     */
    internal class Detected(val strict: Map<String, UnsupportedShaclFeature>, val candidateOnly: Map<RdfResource, String>)

    /**
     * Recognised SHACL constructs the native engine cannot evaluate. They are reported instead of silently
     * compiling to something that always conforms (or targets every node): blank nodes at node expression positions
     * that are no node expression (at `sh:deactivated` of a shape or a reifier too), SPARQL expressions as `sh:targetWhere` values,
     * and `sh:target`. Node-expression positions are only inspected on shape-reachable nodes, so data triples that
     * share the shapes graph (`validate(g, g)`, discovered shapes graphs) are never mistaken for expressions. The
     * shapes only the candidates of computed shape references reach do not fail the run: their constructs are
     * [Detected.candidateOnly], which fails an expression that validates against such a shape.
     */
    internal fun detectUnsupportedFeatures(
        index: ShapeGraphIndex,
        nodeShapes: List<RdfResource>,
        extraRoots: List<RdfResource>,
        functionBodies: List<NodeExpression>,
        exprShapes: ExpressionShapes,
        budget: ValidationBudget,
    ): Detected {
        val out = LinkedHashMap<String, UnsupportedShaclFeature>()
        val candidateOnly = LinkedHashMap<RdfResource, String>()
        val functions = LinkedHashSet<Iri>()
        // The shapes of the node expression bodies of the functions SPARQL queries may call are compiled, so they are
        // shapes the run validates against, reachable from nothing else maybe; so are the candidates of a body that
        // computes its shapes.
        val bodyShapes = functionBodies.flatMap { budget.tick("feature detection"); exprShapes.strict(it) }
        val bodiesNeedCandidates = functionBodies.any { exprShapes.computes(it) }
        val reach = shapeReachableNodes(
            index, nodeShapes + extraRoots + index.subjects(RDF.type, SHACL.PropertyShape) + bodyShapes, exprShapes, budget,
        )
        val shapes = reach.shapes
        val candidateShapes =
            if (reach.needsCandidates || bodiesNeedCandidates) shapeReachableNodes(index, exprShapes.allCandidates - shapes, exprShapes, budget).shapes - shapes else emptySet()
        fun expression(term: RdfTerm) =
            if (isNodeExpression(term, index)) UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION else UnsupportedShaclFeature.NODE_EXPRESSION
        for (t in index.triples) {
            budget.tick("feature detection")
            if (t.predicate == ShaclSparqlTerms.bodyExpression && t.subject is Iri) functions.add(t.subject as Iri)
            // A reifier deactivates the constraint of the parameter triple it reifies, whose subject is a shape (on
            // another triple it has no effect, so it is not inspected). So does a SPARQL-based constraint, the value of
            // sh:sparql of a shape.
            val owner = if (t.predicate == SHACL.deactivated) {
                val reified = index.objects(t.subject, RDF.reifies).filterIsInstance<TripleTerm>()
                    .filter { isParameterPredicate(it.triple.predicate, index) }.map { it.triple.subject } +
                    index.subjects(SHACL.sparql, t.subject)
                reified.firstOrNull { it in shapes } ?: reified.firstOrNull { it in candidateShapes } ?: t.subject
            } else {
                t.subject
            }
            if (owner !in shapes && owner !in candidateShapes) continue
            val (description, feature) = unsupportedFeature(t, index, ::expression) ?: continue
            if (owner in shapes) {
                out[description] = feature
            } else {
                candidateOnly[owner] = candidateOnly[owner]?.let { "$it; $description" } ?: description
            }
        }
        // Functions whose body is a SPARQL or other node expression are evaluated (SparqlFunctionDeclarations). A
        // declared function library is harmless; calling one whose body is no node expression at all from SPARQL is
        // not evaluable.
        functions.retainAll { budget.tick("feature detection"); !SparqlFunctionDeclarations.compiles(it, index) }
        for (function in functions) {
            if (SparqlFunctionDeclarations.calledFromSparql(function, index)) {
                out["SHACL 1.2 function $function (sh:bodyExpression) called from a SPARQL query"] = UnsupportedShaclFeature.SHACL_FUNCTION
            }
        }
        return Detected(out, candidateOnly)
    }

    /** The unsupported construct the shape triple [t] declares, as a description and its category, or null. */
    private fun unsupportedFeature(
        t: RdfTriple,
        index: ShapeGraphIndex,
        expression: (RdfTerm) -> UnsupportedShaclFeature,
    ): Pair<String, UnsupportedShaclFeature>? =
        when {
            t.predicate == ShaclSparqlTerms.values && !isSupportedExpression(t.obj, index) ->
                "sh:values node expression on ${t.subject}" to expression(t.obj)
            t.predicate == ShaclSparqlTerms.defaultValue && !isSupportedExpression(t.obj, index) ->
                "sh:defaultValue node expression on ${t.subject}" to expression(t.obj)
            t.predicate == shExpression && !isSupportedExpression(t.obj, index) ->
                "sh:expression constraint on ${t.subject}" to expression(t.obj)
            t.predicate == shTarget ->
                "sh:target (SPARQL-based or custom target) on ${t.subject}" to UnsupportedShaclFeature.CUSTOM_TARGET
            t.predicate == SHACL.targetWhere && isNodeExpression(t.obj, index) ->
                "sh:targetWhere with a SPARQL node expression on ${t.subject}" to UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION
            t.predicate == SHACL.targetNode && isTargetNodeExpression(t.obj, index) && !isSupportedExpression(t.obj, index) ->
                "sh:targetNode with a node expression on ${t.subject}" to expression(t.obj)
            t.predicate == SHACL.nodeByExpression && t.obj is BlankNode && !isSupportedExpression(t.obj, index) ->
                "sh:nodeByExpression with a computed node expression on ${t.subject}" to expression(t.obj)
            t.predicate in DynamicParameters.predicates && isUnsupportedParameterExpression(t.predicate, t.obj, index) ->
                "${DynamicParameters.displayName(t.predicate)} node expression on ${t.subject} (the constraints that use it are left out)" to
                    expression(t.obj)
            t.predicate in index.sparqlComponents.dynamicParameterPaths() && isUnsupportedParameterExpression(t.predicate, t.obj, index) ->
                "<${t.predicate.value}> node expression on ${t.subject} (the constraints that use it are left out)" to expression(t.obj)
            // SHACL 1.2 Core §3.1.6: the sh:deactivated of a shape, or of a reifier of a parameter triple (one
            // constraint), may be any node expression.
            t.predicate == SHACL.deactivated && t.obj is BlankNode && !isSupportedExpression(t.obj, index) ->
                "sh:deactivated node expression on ${t.subject}" to expression(t.obj)
            (t.predicate == SHACL.reifierShape || (t.predicate == SHACL.reificationRequired && mayBeTrue(t.obj, index))) &&
                hasNonPredicatePath(t.subject, index) -> {
                val parameter = if (t.predicate == SHACL.reifierShape) "sh:reifierShape" else "sh:reificationRequired"
                "$parameter on the non-predicate path of ${t.subject} (reifier constraints need a predicate IRI as sh:path)" to
                    UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH
            }
            else -> null
        }

    /**
     * True when the `sh:reificationRequired` value [term] is, or may be, true: the constant true, or a node expression
     * (Node Expressions §8), reported like the constant. A blank node that is no node expression stays the compile
     * error of [DynamicParameters.value].
     */
    private fun mayBeTrue(term: RdfTerm, index: ShapeGraphIndex): Boolean =
        isLexicallyTrue(term) || (term is BlankNode && isSupportedExpression(term, index))

    /**
     * True for an active property shape whose `sh:path` is not a predicate IRI. `sh:reifierShape` and
     * `sh:reificationRequired` constrain the reifiers of the triples a path traverses, which the engine only knows
     * for predicate paths (`focus path value`): on any other path they would silently constrain nothing.
     */
    private fun hasNonPredicatePath(shape: RdfResource, index: ShapeGraphIndex): Boolean {
        if (isDeactivated(shape, index)) return false
        val paths = index.objects(shape, SHACL.path)
        return paths.isNotEmpty() && paths.any { it !is Iri }
    }
}
