package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlSelect
import org.apache.jena.graph.Node
import org.apache.jena.query.Dataset
import org.apache.jena.query.Query
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionDatasetBuilder
import org.apache.jena.query.QueryFactory
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
import org.apache.jena.sparql.expr.E_Call
import org.apache.jena.sparql.expr.E_Function
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprFunctionN
import org.apache.jena.sparql.expr.ExprFunctionOp
import org.apache.jena.sparql.expr.ExprVisitor
import org.apache.jena.sparql.expr.ExprVisitorBase
import org.apache.jena.sparql.expr.aggregate.AggCustom
import org.apache.jena.sparql.expr.aggregate.Aggregator
import org.apache.jena.sparql.function.Function
import org.apache.jena.sparql.function.FunctionFactory
import org.apache.jena.sparql.function.FunctionRegistry
import org.apache.jena.sparql.pfunction.PropertyFunctionFactory
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry
import org.apache.jena.sparql.service.ServiceExecutorRegistry
import org.apache.jena.sparql.util.MappedLoader

/**
 * The sandbox of a [com.geoknoesis.kastor.rdf.SandboxedSelectQuery] on Jena: what the query may evaluate is an
 * **allowlist**, not Jena's whole engine.
 *
 * - **Syntax:** the text is parsed with Jena's default syntax, which SHACL's generated queries need (the
 *   `SAMEVALUE` keyword of `sparql:sameValue` is not in Jena's strict SPARQL 1.2 grammar yet).
 * - **Static check** ([check]) on the algebra Jena evaluates, so no second parser can read the text differently:
 *   `SERVICE` (it would contact another endpoint), ARQ's `CALL(iri, ...)` (it builds a function IRI from data at run
 *   time), a function or property function that Jena would load as a class by name (`java:` IRIs, and ARQ's `afn:` /
 *   `apf:` namespaces, which Jena maps to its own classes), and custom aggregates are refused. Sub-patterns of `EXISTS` / `NOT EXISTS`, sort conditions, `GROUP BY` expressions and
 *   aggregate arguments are checked explicitly, whatever the walker does with them.
 * - **Run time** ([restrict]): the functions the query can call are the SPARQL built-ins (which Jena evaluates
 *   without its registry), the functions of [STANDARD_FUNCTION_NAMESPACES] that Jena has registered (XSD casts, XPath
 *   `fn:` and `math:` functions, SPARQL's own namespace), and the query's custom functions. Every other IRI is an
 *   unknown function (a SPARQL error), however it was built: Jena's extension functions such as `afn:wait` (which
 *   sleeps through the query timeout and clears the thread's interrupt flag), `afn:print` or `afn:context`, and
 *   functions other code registered globally. Nothing is loaded and nothing is registered. Property functions are
 *   off (`rdfs:member` or `list:member` are matched as ordinary predicates, as in standard SPARQL) and `SERVICE`
 *   has no executor.
 */
internal object JenaQuerySandbox {

    /**
     * Namespaces of the registered functions a sandboxed query may call: XSD casts, XPath functions and operators
     * (`fn:`, `math:`) and the SPARQL namespace. Jena's implementations of them are pure, deterministic (except the
     * clock and random built-ins SPARQL itself has) and bounded by their arguments.
     */
    val STANDARD_FUNCTION_NAMESPACES: List<String> = listOf(
        "http://www.w3.org/2001/XMLSchema#",
        "http://www.w3.org/2005/xpath-functions#",
        "http://www.w3.org/2005/xpath-functions/math#",
        "http://www.w3.org/ns/sparql#",
    )

    /** Parses the text of a sandboxed query (see [parseSelect]) and [check]s it. */
    fun parseChecked(sparql: String): Query = QueryFactory.create(sparql).also { check(it) }

    fun check(query: Query) = check(Algebra.compile(query))

    /**
     * Parses [query] once with [parse], so a [SandboxedSelectQuery] is checked on the very query that is executed. A
     * refused sandboxed query fails with [IllegalArgumentException] before evaluation.
     */
    fun parseSelect(query: SparqlSelect, parse: () -> Query): Query {
        val parsed = parse()
        if (query is SandboxedSelectQuery) check(parsed)
        return parsed
    }

    /**
     * An execution of [parsed] over [dataset], restricted ([restrict]) for a sandboxed [query], whose custom functions
     * then run their nested queries until [deadline] (a [clock] value; [Long.MAX_VALUE] for none).
     */
    fun execution(dataset: Dataset, query: SparqlSelect, parsed: Query, deadline: Long, clock: () -> Long): QueryExecutionDatasetBuilder {
        val builder = QueryExecution.dataset(dataset).query(parsed)
        return if (query is SandboxedSelectQuery) {
            restrict(builder, query.functions, 0, deadline, clock)
        } else {
            builder
        }
    }

    /** [now] plus [timeout] (at least 1 ns); [Long.MAX_VALUE] (no deadline) when that does not fit in a Long. */
    fun deadlineAfter(now: Long, timeout: java.time.Duration): Long = try {
        Math.addExact(now, timeout.toNanos().coerceAtLeast(1)).let { if (it == Long.MAX_VALUE) it - 1 else it }
    } catch (e: ArithmeticException) {
        Long.MAX_VALUE
    }

    /**
     * Runs a sandboxed query with registries that cannot reach outside the store, whatever IRI the query computes at
     * run time (should a function IRI computed from data, as by ARQ's `CALL`, ever get past [check]): only standard functions ([STANDARD_FUNCTION_NAMESPACES]) already registered with Jena
     * and the custom [functions] are found, so no class is loaded and no extension function runs; there are no
     * property functions, and SERVICE has no executor.
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
        .set(ARQConstants.registryPropertyFunctions, NoPropertyFunctions())
        .set(ARQConstants.registryServiceExecutors, ServiceExecutorRegistry())

    /**
     * The standard functions [base] has registered, then the custom [functions]; nothing is ever loaded or registered
     * (only [FunctionRegistry.isRegistered] entries of [base] are read, never its class-loading lookup).
     */
    private class SandboxFunctions(
        private val base: FunctionRegistry,
        private val functions: SparqlFunctionLibrary,
        private val depth: Int,
        private val deadline: Long,
        private val clock: () -> Long,
    ) : FunctionRegistry() {
        override fun get(uri: String): FunctionFactory? = getFunctionFactory(uri)
        override fun getFunctionFactory(uri: String): FunctionFactory? {
            if (isStandard(uri) && base.isRegistered(uri)) base.getFunctionFactory(uri)?.let { return it }
            val function = functions.function(uri) ?: return null
            return FunctionFactory { JenaLibraryFunction(function, depth, deadline, clock) }
        }
        override fun getFunction(uri: String): Function? = getFunctionFactory(uri)?.create(uri)
        override fun isRegistered(uri: String): Boolean = getFunctionFactory(uri) != null
    }

    /** No property function at all: every predicate is matched as data, and nothing is loaded by name. */
    private class NoPropertyFunctions : PropertyFunctionRegistry() {
        override fun get(uri: String): PropertyFunctionFactory? = null
        override fun manages(uri: String): Boolean = false
        override fun isRegistered(uri: String): Boolean = false
    }

    private fun check(op: Op) {
        val exprs = object : ExprVisitorBase() {
            override fun visit(func: ExprFunctionN) {
                if (func is E_Call) refuse("CALL, which computes a function IRI at run time")
                (func as? E_Function)?.functionIRI?.let { if (loadsClass(it)) refuse("the Jena or Java function <$it>") }
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
        if (aggregator is AggCustom) refuse("the custom aggregate <${aggregator.iri}>")
        aggregator.exprList?.forEach { Walker.walk(it, exprs) }
    }

    private fun predicate(node: Node) {
        if (node.isURI && loadsClass(node.uri)) refuse("the Jena or Java property function <${node.uri}>")
    }

    private fun isStandard(iri: String) = STANDARD_FUNCTION_NAMESPACES.any(iri::startsWith)

    /**
     * Whether Jena would resolve [iri] as a function or property function by loading a class: a `java:` IRI, or one
     * in ARQ's own function and property-function namespaces, which Jena maps to its library classes.
     */
    private fun loadsClass(iri: String) =
        iri.startsWith(ARQConstants.javaClassURIScheme) ||
            MappedLoader.mapDynamicURI(iri)?.startsWith(ARQConstants.javaClassURIScheme) == true

    private fun refuse(what: String): Nothing =
        throw IllegalArgumentException("A sandboxed query may not use $what")
}
