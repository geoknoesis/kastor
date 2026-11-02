package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.query.Query
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.core.TriplePath
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.expr.E_Exists
import org.apache.jena.sparql.expr.E_NotExists
import org.apache.jena.sparql.expr.Expr
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprFunction
import org.apache.jena.sparql.expr.ExprFunction1
import org.apache.jena.sparql.expr.ExprFunction2
import org.apache.jena.sparql.expr.ExprFunction3
import org.apache.jena.sparql.expr.ExprFunctionN
import org.apache.jena.sparql.expr.ExprFunctionOp
import org.apache.jena.sparql.expr.ExprLib
import org.apache.jena.sparql.expr.ExprList
import org.apache.jena.sparql.expr.ExprTransformCopy
import org.apache.jena.sparql.expr.ExprTransformer
import org.apache.jena.sparql.expr.ExprTripleTerm
import org.apache.jena.sparql.expr.ExprVar
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.graph.NodeTransform
import org.apache.jena.sparql.syntax.Element
import org.apache.jena.sparql.syntax.ElementAssign
import org.apache.jena.sparql.syntax.ElementBind
import org.apache.jena.sparql.syntax.ElementData
import org.apache.jena.sparql.syntax.ElementFilter
import org.apache.jena.sparql.syntax.ElementPathBlock
import org.apache.jena.sparql.syntax.ElementSubQuery
import org.apache.jena.sparql.syntax.ElementTriplesBlock
import org.apache.jena.sparql.syntax.ElementUnfold
import org.apache.jena.sparql.syntax.ElementVisitorBase
import org.apache.jena.sparql.syntax.ElementWalker
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A deep copy of a parsed query with variables replaced by nodes ([substitute]), for the query templates of
 * [JenaQueryTemplates].
 *
 * Unlike Jena's own `QueryTransformOps.replaceVars`, it replaces a variable inside a triple term too (`<<( ?v :p :o )>>`
 * in a pattern, a reifier pattern `<< ?v :p :o >>`, or a triple-term expression that is a function argument, a
 * `FILTER`, a `BIND`), and it copies every pattern element and every expression, whether or not it mentions a
 * replaced variable: Jena binds an extension function to the first execution's function registry inside the
 * expression object (`E_Function`), so two executions that shared one would share the sandboxed functions (and their
 * deadline) of the first.
 *
 * It is not trusted to be complete: a template is only used once [JenaQueryTemplates] has compared one substitution
 * with the parse of its text and checked [sharesNothing]. A position it does not handle (a triple-term expression that
 * is a whole projection, `ORDER BY` or `GROUP BY` term, say) makes that comparison fail, and the text is parsed instead.
 */
internal object JenaQueryCopy {

    /** [query] with each variable of [nodes] replaced by its node, a copy that shares no element or expression with it. */
    fun substitute(query: Query, nodes: Map<Var, Node>): Query {
        val nodeTransform = Substitution(nodes)
        val elements = CopyElements(nodeTransform)
        return QueryTransformOps.transform(query, elements, elements.exprs)
    }

    /**
     * Whether [copy] shares no pattern element and no expression with [original]: only variables, constants and
     * constant triple terms, which execution never changes, may be the same objects.
     */
    fun sharesNothing(original: Query, copy: Query): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        collect(original, seen)
        val copied = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        collect(copy, copied)
        return copied.none { it in seen }
    }

    /** Recurses into triple terms, which `NodeTransformSubst` leaves as they are. */
    private class Substitution(private val nodes: Map<Var, Node>) : NodeTransform {
        override fun apply(node: Node): Node = when {
            node is Var -> nodes[node] ?: node
            node.isTripleTerm -> {
                val t = node.triple
                val s = apply(t.subject)
                val p = apply(t.predicate)
                val o = apply(t.`object`)
                if (s === t.subject && p === t.predicate && o === t.`object`) node else NodeFactory.createTripleTerm(s, p, o)
            }
            else -> node
        }
    }

    /** Copies every element (`alwaysCopy`), with [Substitution] applied to its nodes and [exprs] to its expressions. */
    private class CopyElements(private val nodes: NodeTransform) : ElementTransformCopyBase(true) {
        val exprs = CopyExprs(nodes, this)

        override fun transform(el: ElementTriplesBlock): Element =
            ElementTriplesBlock().also { copy -> el.pattern.forEach { copy.addTriple(transform(it)) } }

        override fun transform(el: ElementPathBlock): Element = ElementPathBlock().also { copy ->
            el.pattern.forEach { path ->
                copy.addTriplePath(
                    if (path.isTriple) TriplePath(transform(path.asTriple())) else TriplePath(nodes.apply(path.subject), path.path, nodes.apply(path.`object`)),
                )
            }
        }

        override fun transform(triple: Triple): Triple = Triple.create(nodes.apply(triple.subject), nodes.apply(triple.predicate), nodes.apply(triple.`object`))

        override fun transform(quad: org.apache.jena.sparql.core.Quad): org.apache.jena.sparql.core.Quad =
            org.apache.jena.sparql.core.Quad.create(nodes.apply(quad.graph), nodes.apply(quad.subject), nodes.apply(quad.predicate), nodes.apply(quad.`object`))

        // VALUES rows hold constants only; a variable of the block is never replaced (it would not parse as an IRI).
        override fun transform(el: ElementData): Element = ElementData(ArrayList(el.vars), ArrayList(el.rows))

        override fun transform(el: ElementFilter, expr: Expr): Element = ElementFilter(exprs.term(expr))
        override fun transform(el: ElementBind, v: Var, expr: Expr): Element = ElementBind(v, exprs.term(expr))
        override fun transform(el: ElementAssign, v: Var, expr: Expr): Element = ElementAssign(v, exprs.term(expr))
        override fun transform(el: ElementUnfold, expr: Expr, v1: Var, v2: Var?): Element = ElementUnfold(exprs.term(expr), v1, v2)
    }

    /** Copies every expression (`COPY_ALWAYS`), so no execution meets an expression object another one has bound. */
    private class CopyExprs(private val nodes: NodeTransform, private val elements: CopyElements) : ExprTransformCopy(ExprTransformCopy.COPY_ALWAYS) {
        /** [expr] itself as a triple-term expression with its variables replaced, which `ExprTransformer` passes over. */
        fun term(expr: Expr): Expr = if (expr is ExprTripleTerm) ExprLib.nodeToExpr(nodes.apply(expr.node)) else expr

        private fun terms(args: ExprList): ExprList = ExprList().also { list -> args.forEach { list.add(term(it)) } }

        override fun transform(nv: ExprVar): Expr {
            val node = nodes.apply(nv.asVar())
            return if (node === nv.asVar()) nv else ExprLib.nodeToExpr(node)
        }

        override fun transform(nv: NodeValue): Expr = nv

        override fun transform(func: ExprFunction1, expr1: Expr): Expr = super.transform(func, term(expr1))
        override fun transform(func: ExprFunction2, expr1: Expr, expr2: Expr): Expr = super.transform(func, term(expr1), term(expr2))
        override fun transform(func: ExprFunction3, expr1: Expr, expr2: Expr, expr3: Expr): Expr =
            super.transform(func, term(expr1), term(expr2), term(expr3))
        override fun transform(func: ExprFunctionN, args: ExprList): Expr = super.transform(func, terms(args))

        override fun transform(funcOp: ExprFunctionOp, args: ExprList, opArg: Op): Expr {
            val element = ElementTransformer.transform(funcOp.element, elements, this)
            return when (funcOp) {
                is E_Exists -> E_Exists(element)
                is E_NotExists -> E_NotExists(element)
                else -> throw IllegalStateException("no copy for ${funcOp.functionSymbol.symbol}")
            }
        }

        override fun transform(eAgg: ExprAggregator): Expr {
            val aggregator = eAgg.aggregator
            val args = aggregator.exprList?.let { list -> terms(ExprTransformer.transform(this, list)) }
            return ExprAggregator(eAgg.getVar(), aggregator.copy(args))
        }
    }

    private fun collect(query: Query, into: MutableSet<Any>) {
        query.queryPattern?.let { collect(it, into) }
        query.project.exprs.values.forEach { collect(it, into) }
        query.groupBy.exprs.values.forEach { collect(it, into) }
        query.havingExprs.forEach { collect(it, into) }
        query.orderBy?.forEach { collect(it.expression, into) }
        query.aggregators.forEach { collect(it, into) }
    }

    private fun collect(element: Element, into: MutableSet<Any>) {
        ElementWalker.walk(element, object : ElementVisitorBase() {
            override fun visit(el: ElementTriplesBlock) { into.add(el) }
            override fun visit(el: ElementPathBlock) { into.add(el); into.add(el.pattern) }
            override fun visit(el: ElementFilter) { into.add(el); collect(el.expr, into) }
            override fun visit(el: ElementAssign) { into.add(el); collect(el.expr, into) }
            override fun visit(el: ElementBind) { into.add(el); collect(el.expr, into) }
            override fun visit(el: ElementUnfold) { into.add(el); collect(el.expr, into) }
            override fun visit(el: ElementData) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementUnion) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementOptional) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementLateral) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementGroup) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementDataset) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementNamedGraph) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementExists) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementNotExists) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementMinus) { into.add(el) }
            override fun visit(el: org.apache.jena.sparql.syntax.ElementService) { into.add(el) }
            override fun visit(el: ElementSubQuery) { into.add(el); into.add(el.query); collect(el.query, into) }
        })
    }

    private fun collect(expr: Expr, into: MutableSet<Any>) {
        when (expr) {
            is ExprVar, is NodeValue, is ExprTripleTerm -> Unit
            is ExprAggregator -> { into.add(expr); expr.aggregator.exprList?.forEach { collect(it, into) } }
            is ExprFunctionOp -> { into.add(expr); expr.element?.let { collect(it, into) } }
            is ExprFunction -> { into.add(expr); expr.args.forEach { collect(it, into) } }
            else -> into.add(expr)
        }
    }
}
