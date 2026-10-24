package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.Node
import org.apache.jena.query.Query
import org.apache.jena.sparql.ARQConstants
import org.apache.jena.sparql.algebra.Algebra
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.algebra.OpVisitorBase
import org.apache.jena.sparql.algebra.op.OpBGP
import org.apache.jena.sparql.algebra.op.OpGroup
import org.apache.jena.sparql.algebra.op.OpOrder
import org.apache.jena.sparql.algebra.op.OpService
import org.apache.jena.sparql.algebra.op.OpTriple
import org.apache.jena.sparql.algebra.walker.Walker
import org.apache.jena.sparql.expr.E_Function
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprFunctionN
import org.apache.jena.sparql.expr.ExprFunctionOp
import org.apache.jena.sparql.expr.ExprVisitor
import org.apache.jena.sparql.expr.ExprVisitorBase
import org.apache.jena.sparql.expr.aggregate.Aggregator

/**
 * Checks a parsed [com.geoknoesis.kastor.rdf.SandboxedSelectQuery] on the algebra Jena evaluates, so no second parser
 * can read the text differently: `SERVICE` would contact another endpoint, and a `java:` function or property
 * function would make Jena load and instantiate a class by name. Sub-patterns of `EXISTS` / `NOT EXISTS`, sort
 * conditions, `GROUP BY` expressions and aggregate arguments are checked explicitly, whatever the walker does with them.
 */
internal object JenaQuerySandbox {

    fun check(query: Query) = check(Algebra.compile(query))

    private fun check(op: Op) {
        val exprs = object : ExprVisitorBase() {
            override fun visit(func: ExprFunctionN) {
                (func as? E_Function)?.functionIRI?.let { if (isJava(it)) refuse("the java: function <$it>") }
            }
            override fun visit(funcOp: ExprFunctionOp) = check(funcOp.graphPattern)
            override fun visit(eAgg: ExprAggregator) = aggregate(eAgg.aggregator, this)
        }
        // The walker visits neither sort conditions nor GROUP BY expressions and aggregates: they are walked here.
        val ops = object : OpVisitorBase() {
            override fun visit(opService: OpService) = refuse("SERVICE <${opService.service}>")
            override fun visit(opBGP: OpBGP) = opBGP.pattern.forEach { predicate(it.predicate) }
            override fun visit(opTriple: OpTriple) = predicate(opTriple.triple.predicate)
            override fun visit(opOrder: OpOrder) = opOrder.conditions.forEach { Walker.walk(it.expression, exprs) }
            override fun visit(opGroup: OpGroup) {
                opGroup.groupVars.exprs.values.forEach { Walker.walk(it, exprs) }
                opGroup.aggregators.forEach { aggregate(it.aggregator, exprs) }
            }
        }
        Walker.walk(op, ops, exprs)
    }

    private fun aggregate(aggregator: Aggregator, exprs: ExprVisitor) {
        aggregator.exprList?.forEach { Walker.walk(it, exprs) }
    }

    private fun predicate(node: Node) {
        if (node.isURI && isJava(node.uri)) refuse("the java: property function <${node.uri}>")
    }

    private fun isJava(iri: String) = iri.startsWith(ARQConstants.javaClassURIScheme)

    private fun refuse(what: String): Nothing =
        throw IllegalArgumentException("A sandboxed query may not use $what")
}
