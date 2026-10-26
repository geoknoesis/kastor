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
import org.apache.jena.query.ARQ
import org.apache.jena.sparql.ARQConstants
import org.apache.jena.sparql.algebra.Algebra
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.algebra.OpVisitorBase
import org.apache.jena.sparql.algebra.op.OpBGP
import org.apache.jena.sparql.algebra.op.OpGroup
import org.apache.jena.sparql.algebra.op.OpPath
import org.apache.jena.sparql.algebra.op.OpService
import org.apache.jena.sparql.path.*
import org.apache.jena.sparql.algebra.op.OpTriple
import org.apache.jena.sparql.expr.E_Call
import org.apache.jena.sparql.expr.E_Function
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprFunction
import org.apache.jena.sparql.expr.ExprFunctionOp
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
 * **allowlist**, not Jena's whole engine, and what each expression may cost is bounded.
 *
 * - **Syntax:** the text is parsed with Jena's default syntax, which SHACL's generated queries need (the
 *   `SAMEVALUE` keyword of `sparql:sameValue` is not in Jena's strict SPARQL 1.2 grammar yet).
 * - **Static check** ([check]) on the algebra Jena evaluates, so no second parser can read the text differently:
 *   `SERVICE` (it would contact another endpoint), ARQ's `CALL(iri, ...)` and `fn:apply` (they build a function IRI
 *   from data at run time), a function or property function that Jena would load as a class by name (`java:` IRIs,
 *   and ARQ's `afn:` / `apf:` namespaces, which Jena maps to its own classes), any IRI in ARQ's own namespaces (Jena
 *   runs `http://jena.apache.org/ARQ/<language>Function#name` as a script function without asking any registry,
 *   whether `jena:scripting` is on or off), ARQ's path extensions (`p{n,m}`, `distinct(...)`, ...), custom aggregates, and any expression class that is not on the
 *   allowlist of [JenaCost] are refused. Sub-patterns of `EXISTS` / `NOT EXISTS`, sort conditions, `GROUP BY`
 *   expressions and aggregate arguments are checked explicitly, whatever the walker does with them
 *   ([JenaSandboxBounds.walk]).
 * - **Run time** ([restrict]): the functions the query can call are the SPARQL built-ins (which Jena evaluates
 *   without its registry), the registered standard functions that [JenaCost.FUNCTIONS] lists (XSD casts, XPath `fn:`
 *   and `math:` functions, SPARQL's own namespace), and the query's custom functions. Every other IRI is an unknown
 *   function (a SPARQL error): Jena's extension functions such as `afn:wait` (which sleeps through the query timeout
 *   and clears the thread's interrupt flag), `afn:print` or `afn:context`, and functions other code registered
 *   globally. Nothing is loaded and nothing is registered. Property functions are off (`rdfs:member` or `list:member`
 *   are matched as ordinary predicates, as in standard SPARQL) and `SERVICE` has no executor.
 * - **Bounds** ([JenaSandboxRewrite]): every expression of the plan checks the deadline and cancellation before it
 *   runs, is held to [com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits] (a power, a product, `CONCAT`, case
 *   mapping and the other costly functions are checked before they compute, results after), regular expressions run
 *   with a step budget, and `GROUP_CONCAT` accumulates a bounded number of characters. A query that would exceed a
 *   limit fails with `SandboxLimitException`; one whose plan could not be bounded fails too.
 */
internal object JenaQuerySandbox {

    /** Parses the text of a sandboxed query (see [parseSelect]) and [check]s it. */
    fun parseChecked(sparql: String): Query = QueryFactory.create(JenaLiteralBounds.requireBoundedQuery(sparql)).also { check(it) }

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
     * run time: only the standard functions of [JenaCost.FUNCTIONS] already registered with Jena and the custom
     * [functions] are found, so no class is loaded and no extension function runs; there are no property functions,
     * and SERVICE has no executor. Its plan is bounded by a [JenaSandboxRewrite] (Jena's optimizer is forced on, since
     * the rewrite runs as the optimizer), and its property paths are metered by [JenaSandboxPaths]; call
     * [requireBounded] once the plan is made.
     *
     * The custom [functions] of the query are visible to this one execution only, at nesting [depth], and the query
     * and their nested queries end by [deadline] (a [clock] value; [Long.MAX_VALUE] for none). Per SHACL 1.2 SPARQL
     * Extensions section 7.3, a built-in function wins over a custom function with the same IRI.
     */
    fun restrict(
        builder: QueryExecutionDatasetBuilder,
        functions: SparqlFunctionLibrary,
        depth: Int,
        deadline: Long,
        clock: () -> Long,
    ): QueryExecutionDatasetBuilder {
        val budget = JenaSandboxBudget(deadline, clock)
        return builder
            .set(ARQConstants.registryFunctions, SandboxFunctions(FunctionRegistry.get(), functions, depth, deadline, clock))
            .set(ARQConstants.registryPropertyFunctions, NoPropertyFunctions())
            .set(ARQConstants.registryServiceExecutors, ServiceExecutorRegistry())
            .set(ARQConstants.sysOptimizerFactory, JenaSandboxRewrite(budget))
            .set(ARQConstants.sysOpExecutorFactory, JenaSandboxPaths(budget))
            .set(ARQ.optimization, true)
    }

    /** Fails a sandboxed execution whose plan was not bounded; see [JenaSandboxBounds.requireBounded]. */
    fun requireBounded(exec: QueryExecution) = JenaSandboxBounds.requireBounded(exec)

    /**
     * The standard functions [base] has registered that [JenaCost.FUNCTIONS] allows, then the custom [functions];
     * nothing is ever loaded or registered (only [FunctionRegistry.isRegistered] entries of [base] are read, never its
     * class-loading lookup).
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
            if (JenaCost.ofFunction(uri) != null && base.isRegistered(uri)) base.getFunctionFactory(uri)?.let { return it }
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
        val exprs = object : JenaSandboxBounds.ExprVisitorAdapter() {
            override fun function(func: ExprFunction) {
                when {
                    func is E_Call -> refuse("CALL, which computes a function IRI at run time")
                    func is ExprFunctionOp -> Unit
                    JenaCost.of(func) == null -> refuse("the expression ${func.functionSymbol.symbol}")
                    func is E_Function -> functionIri(func.functionIRI)
                }
            }
            override fun visit(eAgg: ExprAggregator) = aggregate(eAgg.aggregator)

            // A triple-term expression with variables builds a term from its bindings without any bound (a chain of
            // them doubles the term at every step); TRIPLE(?s, ?p, ?o) builds the same term with its size checked.
            override fun tripleTerm(term: org.apache.jena.sparql.expr.ExprTripleTerm) {
                if (!term.node.isConcrete) refuse("the triple-term expression $term with variables (use TRIPLE(?s, ?p, ?o))")
            }
        }
        val ops = object : OpVisitorBase() {
            override fun visit(opService: OpService) = refuse("SERVICE <${opService.service}>")
            override fun visit(opBGP: OpBGP) = opBGP.pattern.forEach { predicate(it.predicate) }
            override fun visit(opTriple: OpTriple) = predicate(opTriple.triple.predicate)
            override fun visit(opGroup: OpGroup) = opGroup.aggregators.forEach { aggregate(it.aggregator) }
            override fun visit(opPath: OpPath) = path(opPath.triplePath.path)
        }
        JenaSandboxBounds.walk(op, exprs, ops)
    }

    private fun functionIri(iri: String) {
        when {
            loadsClass(iri) -> refuse("the Jena or Java function <$iri>")
            ARQ_NAMESPACES.any(iri::startsWith) -> refuse("the Jena function <$iri> (an ARQ extension or script function)")
            iri == FN_APPLY -> refuse("fn:apply, which computes a function IRI at run time")
        }
    }

    /**
     * Refuses ARQ's path extensions (`p{n}`, `p{n,m}`, `distinct(...)`, `multi(...)`, `shortest(...)`), which standard
     * SPARQL does not have, and paths of more than [MAX_PATH_NODES] steps and operators or nested more than
     * [MAX_PATH_DEPTH] deep (a structural limit: Jena compiles and evaluates a path recursively). The **cost** of the
     * standard operators is not bounded here but metered while they run ([JenaSandboxPaths]): every graph read of a
     * path evaluation counts, checks the deadline, and is capped between two solutions.
     */
    private fun path(path: Path) {
        var nodes = 0
        fun count(depth: Int) {
            if (++nodes > MAX_PATH_NODES) refuse("a property path of more than $MAX_PATH_NODES steps and operators")
            if (depth > MAX_PATH_DEPTH) refuse("a property path nested more than $MAX_PATH_DEPTH deep")
        }
        fun depthOf(p: Path, depth: Int) {
            count(depth)
            when (p) {
                is P_Path1 -> depthOf(p.subPath, depth + 1)
                is P_Path2 -> { depthOf(p.left, depth + 1); depthOf(p.right, depth + 1) }
                is P_NegPropSet -> p.nodes.forEach { _ -> count(depth + 1) }
            }
        }
        depthOf(path, 0)
        path.visit(object : PathVisitorBase() {
            override fun visit(path: P_Mod) = refuse("the ARQ path extension ${path}")
            override fun visit(path: P_FixedLength) = refuse("the ARQ path extension ${path}")
            override fun visit(path: P_Distinct) = refuse("the ARQ path extension ${path}")
            override fun visit(path: P_Multi) = refuse("the ARQ path extension ${path}")
            override fun visit(path: P_Shortest) = refuse("the ARQ path extension ${path}")
            override fun visit(path: P_Inverse) = path.subPath.visit(this)
            override fun visit(path: P_ZeroOrOne) = path.subPath.visit(this)
            override fun visit(path: P_ZeroOrMore1) = path.subPath.visit(this)
            override fun visit(path: P_ZeroOrMoreN) = path.subPath.visit(this)
            override fun visit(path: P_OneOrMore1) = path.subPath.visit(this)
            override fun visit(path: P_OneOrMoreN) = path.subPath.visit(this)
            override fun visit(path: P_Alt) = listOf(path.left, path.right).forEach { it.visit(this) }
            override fun visit(path: P_Seq) = listOf(path.left, path.right).forEach { it.visit(this) }
        })
    }

    private fun aggregate(aggregator: Aggregator) {
        if (aggregator is AggCustom) refuse("the custom aggregate <${aggregator.iri}>")
    }

    private fun predicate(node: Node) {
        if (node.isURI && (loadsClass(node.uri) || ARQ_NAMESPACES.any(node.uri::startsWith))) {
            refuse("the Jena or Java property function <${node.uri}>")
        }
    }

    /**
     * Most steps and operators one property path may have, and its deepest nesting: a structural limit only (Jena
     * compiles and evaluates a path recursively); what a path costs is metered by [JenaSandboxPaths].
     */
    private const val MAX_PATH_NODES = 256
    private const val MAX_PATH_DEPTH = 32

    /** ARQ's own namespaces: its function and property-function libraries and its script functions. */
    private val ARQ_NAMESPACES = listOf("http://jena.apache.org/ARQ/", "http://jena.hpl.hp.com/ARQ/")

    private const val FN_APPLY = "http://www.w3.org/2005/xpath-functions#apply"

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
