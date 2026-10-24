package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import org.apache.jena.graph.Node
import org.apache.jena.query.Query
import org.apache.jena.query.QueryExecutionDatasetBuilder
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
import org.apache.jena.sparql.function.Function
import org.apache.jena.sparql.function.FunctionFactory
import org.apache.jena.sparql.function.FunctionRegistry
import org.apache.jena.sparql.pfunction.PropertyFunctionFactory
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry
import org.apache.jena.sparql.service.ServiceExecutorRegistry

/**
 * Checks a parsed [com.geoknoesis.kastor.rdf.SandboxedSelectQuery] on the algebra Jena evaluates, so no second parser
 * can read the text differently: `SERVICE` would contact another endpoint, and a `java:` function or property
 * function would make Jena load and instantiate a class by name. Sub-patterns of `EXISTS` / `NOT EXISTS`, sort
 * conditions, `GROUP BY` expressions and aggregate arguments are checked explicitly, whatever the walker does with them.
 */
internal object JenaQuerySandbox {

    fun check(query: Query) = check(Algebra.compile(query))

    /**
     * Runs a sandboxed query with registries that cannot reach outside the store, whatever IRI the query computes at
     * run time (ARQ's `CALL(iri, …)` builds a function IRI from data, which [check] cannot see): functions and
     * property functions with a `java:` IRI are not found, so no class is loaded, and SERVICE has no executor.
     *
     * The custom [functions] of the query are visible to this one execution only, at nesting [depth], and their nested
     * queries end by [deadline] (a [clock] value; [Long.MAX_VALUE] for none). Per SHACL 1.2 SPARQL Extensions section
     * 7.3, a built-in function wins over a custom function with the same IRI.
     */
    fun restrict(
        builder: QueryExecutionDatasetBuilder,
        functions: SparqlFunctionLibrary,
        depth: Int,
        deadline: Long,
        clock: () -> Long,
    ): QueryExecutionDatasetBuilder = builder
        .set(ARQConstants.registryFunctions, SandboxFunctions(FunctionRegistry.get(), functions, depth, deadline, clock))
        .set(ARQConstants.registryPropertyFunctions, NoJavaPropertyFunctions(PropertyFunctionRegistry.get()))
        .set(ARQConstants.registryServiceExecutors, ServiceExecutorRegistry())

    /** The [base] functions without `java:` ones, then the custom [functions]; nothing is ever registered. */
    private class SandboxFunctions(
        private val base: FunctionRegistry,
        private val functions: SparqlFunctionLibrary,
        private val depth: Int,
        private val deadline: Long,
        private val clock: () -> Long,
    ) : FunctionRegistry() {
        override fun get(uri: String): FunctionFactory? = getFunctionFactory(uri)
        override fun getFunctionFactory(uri: String): FunctionFactory? {
            if (isJava(uri)) return null
            base.getFunctionFactory(uri)?.let { return it }
            val function = functions.function(uri) ?: return null
            return FunctionFactory { JenaLibraryFunction(function, depth, deadline, clock) }
        }
        override fun getFunction(uri: String): Function? = getFunctionFactory(uri)?.create(uri)
        override fun isRegistered(uri: String): Boolean = getFunctionFactory(uri) != null
    }

    private class NoJavaPropertyFunctions(private val base: PropertyFunctionRegistry) : PropertyFunctionRegistry() {
        override fun get(uri: String): PropertyFunctionFactory? = if (isJava(uri)) null else base.get(uri)
        override fun manages(uri: String): Boolean = !isJava(uri) && base.manages(uri)
        override fun isRegistered(uri: String): Boolean = !isJava(uri) && base.isRegistered(uri)
    }

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
