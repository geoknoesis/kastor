package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionCall
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxMemoryBudget
import org.eclipse.rdf4j.common.iteration.CloseableIteration
import org.eclipse.rdf4j.common.order.StatementOrder
import org.eclipse.rdf4j.common.transaction.QueryEvaluationMode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.query.Dataset
import org.eclipse.rdf4j.query.QueryEvaluationException
import org.eclipse.rdf4j.query.QueryInterruptedException
import org.eclipse.rdf4j.query.algebra.FunctionCall
import org.eclipse.rdf4j.query.algebra.QueryRoot
import org.eclipse.rdf4j.query.algebra.Regex
import org.eclipse.rdf4j.query.algebra.ValueExpr
import org.eclipse.rdf4j.query.algebra.Service
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.TupleFunctionCall
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep
import org.eclipse.rdf4j.query.algebra.evaluation.QueryValueEvaluationStep
import org.eclipse.rdf4j.query.algebra.evaluation.RDFStarTripleSource
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategyFactory
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.ConstantOptimizer
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.StandardQueryOptimizerPipeline
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor
import org.eclipse.rdf4j.query.impl.EmptyBindingSet
import org.eclipse.rdf4j.query.parser.ParsedQuery
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.nativerdf.NativeStore

/**
 * Hands the custom SPARQL functions of one sandboxed query to the [KastorEvaluationStrategy] that evaluates it.
 *
 * The repository sets a scope around preparing and evaluating the query ([with]); the first strategy that a Kastor
 * store creates on that thread meanwhile takes it ([claim]) and keeps it for the whole evaluation, including the rows
 * the caller reads later. Nothing else sees it: a scope is per thread, taken once and restored when [with] returns,
 * so neither concurrent queries nor queries that a function body runs on another repository see these functions.
 * RDF4J's global `FunctionRegistry` is never changed.
 */
internal object Rdf4jFunctionScope {

    /**
     * The custom [functions] of a query at nesting [depth] (0 for the query the caller runs), whose nested queries end
     * by [deadline] (a [clock] value; [Long.MAX_VALUE] for none), are prepared by [nested] and run on [dataset]: the
     * dataset that the query's own dataset clauses (or those of a calling query) select, null for Kastor's dataset.
     * [memory] is the budget of computed characters the query shares with its nested queries; null for a fresh one
     * per strategy (the store-wide sandbox, whose queries are unrelated).
     */
    class Scope(
        val functions: SparqlFunctionLibrary,
        val depth: Int,
        val deadline: Long,
        val clock: () -> Long,
        val nested: Rdf4jNestedQueries,
        val dataset: Dataset?,
        val memory: SandboxMemoryBudget? = null,
    )

    private val pending = ThreadLocal<Scope?>()

    /**
     * Runs [block] with a scope that the next strategy created on this thread takes. A [block] that returns while the
     * scope is still unclaimed evaluated the query without Kastor's strategy, so without its sandbox: its result is
     * closed (when it is [AutoCloseable]) and the query fails with [UnsupportedOperationException]. The repository
     * refuses such stores before preparing anything ([Rdf4jQuerySandbox.evaluatesSandboxed]); this is the backstop.
     */
    fun <T> with(
        functions: SparqlFunctionLibrary,
        depth: Int,
        deadline: Long,
        clock: () -> Long,
        nested: Rdf4jNestedQueries,
        dataset: Dataset?,
        block: () -> T,
    ): T {
        val previous = pending.get()
        val scope = Scope(functions, depth, deadline, clock, nested, dataset, SandboxMemoryBudget())
        pending.set(scope)
        try {
            val result = block()
            if (pending.get() === scope) {
                (result as? AutoCloseable)?.close()
                throw UnsupportedOperationException(Rdf4jQuerySandbox.FOREIGN_STORE)
            }
            return result
        } finally {
            if (previous == null) pending.remove() else pending.set(previous)
        }
    }

    /** Takes the scope waiting on this thread: only the first strategy created within [with] evaluates its functions. */
    fun claim(): Scope? = pending.get()?.also { pending.set(null) }
}

/** A nested query of a custom function, prepared by the repository the calling query runs on. */
internal class Rdf4jNestedQuery(
    val parsed: ParsedQuery,
    /** The dataset to evaluate [parsed] on; null for the union of every context. */
    val dataset: Dataset?,
    /** The dataset its own nested queries run on ([Rdf4jFunctionScope.Scope.dataset]). */
    val confinedTo: Dataset?,
    /** Bound natively: variables that stand for constants the query text cannot spell. */
    val bindings: org.eclipse.rdf4j.query.BindingSet,
    /** Variables that are not returned in rows. */
    val hidden: Set<String>,
)

/** Prepares the nested queries of custom functions as the repository prepares its own sandboxed queries. */
internal fun interface Rdf4jNestedQueries {
    /**
     * Applies [bindings] to [query] (the initial-bindings contract of a top-level query), refuses it with
     * [IllegalArgumentException] as a top-level sandboxed query is refused, and parses it. It runs on [caller], the
     * dataset of the calling query (null for Kastor's dataset): its own `FROM` / `FROM NAMED` select among the named
     * graphs of that dataset, and any other graph reads as empty, as with Jena's dynamic datasets.
     */
    fun prepare(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>, caller: Dataset?): Rdf4jNestedQuery
}

/** A [MemoryStore] whose queries use [KastorEvaluationStrategyFactory] and whose writes are bounded ([Rdf4jBoundedWrites]). */
internal fun kastorMemoryStore(): MemoryStore = BoundedMemoryStore().apply {
    evaluationStrategyFactory = KastorEvaluationStrategyFactory(federatedServiceResolver, QueryEvaluationMode.STANDARD)
}

/** A [NativeStore] whose queries use [KastorEvaluationStrategyFactory] (strict, as its default factory is), writes bounded. */
internal fun kastorNativeStore(location: String): NativeStore = BoundedNativeStore(java.io.File(location)).apply {
    evaluationStrategyFactory = KastorEvaluationStrategyFactory(federatedServiceResolver, QueryEvaluationMode.STRICT)
}

/**
 * A [MemoryStore] on which **every** query is sandboxed, as a [SandboxedSelectQuery] without custom functions that ends
 * by [deadline] (a [System.nanoTime] value; [Long.MAX_VALUE] for none): the base store of the RDF4J SHACL validator,
 * whose `ShaclSail` runs the SPARQL of an untrusted shapes graph (`sh:sparql`, `sh:SPARQLTarget`).
 */
internal fun sandboxedMemoryStore(deadline: Long): MemoryStore = BoundedMemoryStore().apply {
    val scope = Rdf4jFunctionScope.Scope(
        SparqlFunctionLibrary.EMPTY, 0, deadline, System::nanoTime,
        { _, _, _ -> throw IllegalStateException("A store-wide sandbox has no custom functions") }, null,
    )
    evaluationStrategyFactory = KastorEvaluationStrategyFactory(federatedServiceResolver, QueryEvaluationMode.STANDARD, scope)
}

/**
 * The factory a Kastor store creates its strategies with: RDF4J's own (default or strict) evaluation, plus the custom
 * functions of the sandboxed query being evaluated, if any ([Rdf4jFunctionScope]), or else the store-wide sandbox
 * [everyQuery] ([sandboxedMemoryStore]). An inferencer or SHACL sail that wraps the store evaluates queries with the
 * store's connection, so with this factory too.
 */
internal class KastorEvaluationStrategyFactory(
    resolver: FederatedServiceResolver?,
    private val mode: QueryEvaluationMode,
    private val everyQuery: Rdf4jFunctionScope.Scope? = null,
) : DefaultEvaluationStrategyFactory(resolver) {

    override fun createEvaluationStrategy(dataset: Dataset?, tripleSource: TripleSource, statistics: EvaluationStatistics): EvaluationStrategy {
        val scope = Rdf4jFunctionScope.claim() ?: everyQuery
        // A sandboxed query checks its exact deadline on every statement it reads, as its nested queries do.
        val source = if (scope == null || scope.deadline == Long.MAX_VALUE) tripleSource
        else DeadlineTripleSource.of(tripleSource, Rdf4jSandboxBudget(scope.deadline, scope.clock)::check)
        val strategy = KastorEvaluationStrategy(
            source, dataset, federatedServiceResolver, querySolutionCacheThreshold, statistics, isTrackResultSize, mode, scope,
        )
        optimizerPipeline.ifPresent(strategy::setOptimizerPipeline)
        return strategy
    }
}

/**
 * RDF4J's evaluation, plus the custom functions of [scope] (null: an ordinary query, evaluated exactly as RDF4J does).
 *
 * For a sandboxed query (a [scope]):
 * - a SPARQL built-in or XSD cast that RDF4J implements itself ([Rdf4jSandboxFunctions.isStandard]) is evaluated by
 *   RDF4J: a built-in wins over a custom function (SHACL 1.2 SPARQL Extensions, section 7.3). Any other function in
 *   RDF4J's global `FunctionRegistry` (registered by an application or a library) is not visible;
 * - otherwise a custom function of [scope] is evaluated with its arguments ([SparqlFunction.bindArguments]); every
 *   failure is a SPARQL error ([ValueExprEvaluationException]: `BIND` leaves the variable unbound, `FILTER` is false),
 *   except an interrupted nested query or body ([QueryInterruptedException]; a body's [InterruptedException] keeps
 *   the thread's interrupt flag), which stops this query;
 * - any other function IRI is an error when it is called, as on Jena (RDF4J fails the whole query otherwise);
 * - calls with constant arguments are not folded while the query is optimized, so that each call runs its body once
 *   and RDF4J does not fail on an unknown function before evaluation;
 * - `SERVICE`, custom aggregates and tuple functions fail when the query is optimized or prepared, and no federated
 *   service is ever resolved: the static check ([Rdf4jQuerySandbox.check]) refuses `SERVICE` and custom aggregates
 *   first, this is the second barrier.
 */
internal class KastorEvaluationStrategy(
    tripleSource: TripleSource,
    dataset: Dataset?,
    resolver: FederatedServiceResolver?,
    private val cacheThreshold: Long,
    private val statistics: EvaluationStatistics,
    private val trackResults: Boolean,
    mode: QueryEvaluationMode,
    private val scope: Rdf4jFunctionScope.Scope?,
) : DefaultEvaluationStrategy(tripleSource, dataset, resolver, cacheThreshold, statistics, trackResults) {

    init {
        queryEvaluationMode = mode
    }

    /** The bounds of a sandboxed query's expressions ([Rdf4jEvaluationBounds]); null for an ordinary query. */
    private val bounds: Rdf4jEvaluationBounds? =
        scope?.let { Rdf4jEvaluationBounds(Rdf4jSandboxBudget(it.deadline, it.clock, it.memory ?: SandboxMemoryBudget()), tripleSource.valueFactory) }

    override fun optimize(expr: TupleExpr, evaluationStatistics: EvaluationStatistics, bindings: org.eclipse.rdf4j.query.BindingSet): TupleExpr {
        if (scope != null) {
            Rdf4jQuerySandbox.refusal(expr)?.let { throw QueryEvaluationException(it) }
            // No expression is evaluated while the query is optimized: constant folding would compute it unbounded.
            val standard = StandardQueryOptimizerPipeline(this, tripleSource, evaluationStatistics)
            setOptimizerPipeline { standard.optimizers.filterNot { it is ConstantOptimizer } }
        }
        return super.optimize(expr, evaluationStatistics, bindings)
    }

    /**
     * Every row an operator of a sandboxed query produces checks the deadline and interruption, as Jena checks them per
     * binding: a cross product of `VALUES` tables or an aggregate over one reads no statement and may evaluate no
     * expression, so neither of the other checks would stop it.
     */
    override fun precompile(expr: TupleExpr, context: QueryEvaluationContext): QueryEvaluationStep {
        val step = super.precompile(expr, context)
        val budget = bounds?.budget ?: return step
        return QueryEvaluationStep { bindings -> CheckedIteration(step.evaluate(bindings), budget::check) }
    }

    override fun precompile(expr: ValueExpr, context: QueryEvaluationContext): QueryValueEvaluationStep {
        val step = super.precompile(expr, context)
        return bounds?.wrap(expr, step) ?: step
    }

    override fun prepare(node: Regex, context: QueryEvaluationContext): QueryValueEvaluationStep {
        val bounds = bounds ?: return super.prepare(node, context)
        val text = precompile(node.arg, context)
        val pattern = precompile(node.patternArg, context)
        val flags = node.flagsArg?.let { precompile(it, context) }
        val compiled = Rdf4jEvaluationBounds.PatternCache()
        return QueryValueEvaluationStep { b ->
            bounds.failing { bounds.regex(text.evaluate(b), pattern.evaluate(b), flags?.evaluate(b), compiled) }
        }
    }

    /** A built-in function call of a sandboxed query whose cost is checked before it computes ([Rdf4jCost]). */
    private fun builtIn(node: FunctionCall, context: QueryEvaluationContext, bounds: Rdf4jEvaluationBounds): QueryValueEvaluationStep {
        val cost = Rdf4jCost.ofFunction(node.uri)
        if (cost == Rdf4jCost.GENERIC || cost == Rdf4jCost.COPYING) return super.prepare(node, context)
        val function = org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry.getInstance().get(node.uri).get()
        val args = node.args.map { precompile(it, context) }
        val compiled = Rdf4jEvaluationBounds.PatternCache()
        return QueryValueEvaluationStep { b ->
            val values = args.map { it.evaluate(b) }
            bounds.failing {
                if (cost == Rdf4jCost.REPLACE) bounds.replace(values, compiled)
                else {
                    bounds.precheck(cost, node.uri, values)
                    function.evaluate(tripleSource, *values.toTypedArray())
                }
            }
        }
    }

    override fun prepare(service: Service, context: QueryEvaluationContext): QueryEvaluationStep {
        if (scope != null) throw QueryEvaluationException("A sandboxed query may not use SERVICE")
        return super.prepare(service, context)
    }

    override fun prepare(call: TupleFunctionCall, context: QueryEvaluationContext): QueryEvaluationStep {
        if (scope != null) throw QueryEvaluationException("A sandboxed query may not call the tuple function <${call.uri}>")
        return super.prepare(call, context)
    }

    override fun getService(serviceUrl: String): FederatedService {
        if (scope != null) throw QueryEvaluationException("A sandboxed query may not use SERVICE <$serviceUrl>")
        return super.getService(serviceUrl)
    }

    override fun prepare(node: FunctionCall, context: QueryEvaluationContext): QueryValueEvaluationStep {
        val iri = node.uri
        if (scope == null) return super.prepare(node, context)
        if (isBuiltIn(iri)) return builtIn(node, context, bounds!!)
        val function = scope.functions.function(iri) ?: return QueryValueEvaluationStep.Fail("Unknown function '$iri'")
        val arguments = node.args.map { precompile(it, context) }
        return QueryValueEvaluationStep { bindings -> call(function, scope, arguments, bindings) }
    }

    private fun call(
        function: SparqlFunction,
        scope: Rdf4jFunctionScope.Scope,
        steps: List<QueryValueEvaluationStep>,
        bindings: org.eclipse.rdf4j.query.BindingSet,
    ): Value {
        val evaluated = steps.map { step ->
            try {
                step.evaluate(bindings)?.let(Rdf4jTerms::fromRdf4jValue)
            } catch (e: ValueExprEvaluationException) {
                null
            } catch (e: IllegalArgumentException) {
                null // a value Kastor cannot represent
            }
        }
        val arguments = function.bindArguments(evaluated)
            ?: throw ValueExprEvaluationException("Bad arguments for the custom function <${function.iri}>")
        val result = try {
            function.body(Call(arguments, scope))
        } catch (e: QueryInterruptedException) {
            throw e
        } catch (e: Rdf4jSandboxLimitExceeded) {
            throw e
        } catch (e: ValueExprEvaluationException) {
            throw e
        } catch (e: InterruptedException) {
            // The thread was asked to stop: keep the request and stop the calling query.
            Thread.currentThread().interrupt()
            throw QueryInterruptedException(e)
        } catch (e: Exception) {
            // An interruption or an exceeded sandbox limit of a nested query stops the calling query too.
            generateSequence<Throwable>(e) { it.cause }
                .firstOrNull { it is QueryInterruptedException || it is Rdf4jSandboxLimitExceeded }?.let { throw it }
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SandboxLimitException>().firstOrNull()
                ?.let { throw Rdf4jSandboxLimitExceeded(it) }
            throw ValueExprEvaluationException("The custom function <${function.iri}> failed: ${e.message}", e)
        } ?: throw ValueExprEvaluationException("The custom function <${function.iri}> has no result")
        return try {
            Rdf4jTerms.toRdf4jValue(result)
        } catch (e: IllegalArgumentException) {
            throw ValueExprEvaluationException("The custom function <${function.iri}> returned ${e.message}", e)
        }
    }

    private inner class Call(override val arguments: List<RdfTerm?>, private val scope: Rdf4jFunctionScope.Scope) : SparqlFunctionCall {

        override fun <T> select(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>, consume: (Sequence<BindingSet>) -> T): T {
            if (scope.depth >= SparqlFunction.MAX_FUNCTION_DEPTH) {
                throw ValueExprEvaluationException(
                    "Custom SPARQL functions nest deeper than ${SparqlFunction.MAX_FUNCTION_DEPTH}",
                )
            }
            val nested = scope.nested.prepare(query, bindings, scope.dataset)
            val deadline = scope.deadline
            fun checkDeadline() {
                if (deadline != Long.MAX_VALUE && scope.clock() - deadline >= 0) {
                    throw QueryInterruptedException("A nested query of a custom SPARQL function ran out of time")
                }
            }
            checkDeadline()
            // Every statement the nested query reads checks the deadline too, so it also stops while it computes a row.
            val source = if (deadline == Long.MAX_VALUE) tripleSource else DeadlineTripleSource.of(tripleSource, ::checkDeadline)
            val strategy = KastorEvaluationStrategy(
                source, nested.dataset, serviceResolver, cacheThreshold, statistics, trackResults, queryEvaluationMode,
                Rdf4jFunctionScope.Scope(
                    query.functions, scope.depth + 1, deadline, scope.clock, scope.nested, nested.confinedTo, bounds?.budget?.memory,
                ),
            )
            strategy.setCollectionFactory(collectionFactory)
            val optimized = strategy.optimize(QueryRoot(nested.parsed.tupleExpr.clone()), statistics, nested.bindings)
            return strategy.precompile(optimized).evaluate(EmptyBindingSet.getInstance()).use { rows ->
                consume(rows.asSequence().map { row ->
                    checkDeadline()
                    val names = row.bindingNames.filterNot { it in nested.hidden }
                    MapBindingSet(names.associateWith { Rdf4jTerms.fromRdf4jValue(row.getValue(it)) }) as BindingSet
                })
            }
        }
    }

    private companion object {
        fun isBuiltIn(iri: String) = Rdf4jSandboxFunctions.isStandard(iri)

        /** Whether [expr] calls a function that is not one of RDF4J's built-ins. */
        fun callsCustomFunction(expr: TupleExpr): Boolean {
            var found = false
            expr.visit(object : AbstractQueryModelVisitor<RuntimeException>() {
                override fun meet(node: FunctionCall) {
                    if (!isBuiltIn(node.uri)) found = true else super.meet(node)
                }
            })
            return found
        }
    }
}

/**
 * [source], with [check] (which throws [QueryInterruptedException] once the deadline has passed) run before every
 * statement or triple term is read. RDF4J's own time limit (`TimeLimitIteration`) is only checked between result rows,
 * so it does not stop a query while it computes a row (an aggregate, a sort, a join); this does.
 *
 * A sandboxed query with a deadline reads its store through this source too, at the top level as in nested queries,
 * and every value expression it evaluates checks the deadline first ([Rdf4jEvaluationBounds]), so work that reads no
 * statements also stops at the deadline: every row an operator produces checks it (as Jena checks cancellation per
 * binding), and a regular expression checks it while it matches.
 *
 * [of] keeps RDF4J's triple-term access: a source that is an [RDFStarTripleSource] (as a store's is) is wrapped as one,
 * because RDF4J matches `<< ?s ?p ?o >>` against `rdf:subject` / `rdf:predicate` / `rdf:object` statements on any other
 * source.
 */
private open class DeadlineTripleSource(private val source: TripleSource, protected val check: () -> Unit) : TripleSource {

    companion object {
        fun of(source: TripleSource, check: () -> Unit): TripleSource =
            if (source is RDFStarTripleSource) DeadlineRdfStarTripleSource(source, check) else DeadlineTripleSource(source, check)
    }

    override fun getStatements(
        subj: Resource?, pred: IRI?, obj: Value?, vararg contexts: Resource?,
    ): CloseableIteration<out Statement> {
        check()
        return checked(source.getStatements(subj, pred, obj, *contexts))
    }

    override fun getStatements(
        order: StatementOrder?, subj: Resource?, pred: IRI?, obj: Value?, vararg contexts: Resource?,
    ): CloseableIteration<out Statement> {
        check()
        return checked(source.getStatements(order, subj, pred, obj, *contexts))
    }

    override fun getSupportedOrders(subj: Resource?, pred: IRI?, obj: Value?, vararg contexts: Resource?): Set<StatementOrder> =
        source.getSupportedOrders(subj, pred, obj, *contexts)

    override fun getComparator(): Comparator<Value>? = source.comparator

    override fun getValueFactory(): ValueFactory = source.valueFactory

    protected fun <E> checked(items: CloseableIteration<out E>): CloseableIteration<E> = CheckedIteration(items, check)
}

/** [items], with [check] run before each is read. */
internal class CheckedIteration<E>(private val items: CloseableIteration<out E>, private val check: () -> Unit) : CloseableIteration<E> {
    override fun hasNext(): Boolean {
        check()
        return items.hasNext()
    }

    override fun next(): E {
        check()
        return items.next()
    }

    override fun remove() = items.remove()

    override fun close() = items.close()
}

/** A [DeadlineTripleSource] over an [RDFStarTripleSource], whose triple terms are read with the same [check]. */
private class DeadlineRdfStarTripleSource(private val source: RDFStarTripleSource, check: () -> Unit) :
    DeadlineTripleSource(source, check), RDFStarTripleSource {

    override fun getRdfStarTriples(subj: Resource?, pred: IRI?, obj: Value?): CloseableIteration<out Triple> {
        check()
        return checked(source.getRdfStarTriples(subj, pred, obj))
    }
}
