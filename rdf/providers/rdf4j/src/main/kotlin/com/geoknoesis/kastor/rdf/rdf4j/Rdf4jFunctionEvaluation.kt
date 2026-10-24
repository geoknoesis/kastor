package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.MAX_FUNCTION_DEPTH
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionCall
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.bindArguments
import org.eclipse.rdf4j.common.transaction.QueryEvaluationMode
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.query.Dataset
import org.eclipse.rdf4j.query.QueryInterruptedException
import org.eclipse.rdf4j.query.algebra.FunctionCall
import org.eclipse.rdf4j.query.algebra.QueryRoot
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy
import org.eclipse.rdf4j.query.algebra.evaluation.QueryValueEvaluationStep
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry
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
     * by [deadline] (a [clock] value; [Long.MAX_VALUE] for none) and are prepared by [nested].
     */
    class Scope(
        val functions: SparqlFunctionLibrary,
        val depth: Int,
        val deadline: Long,
        val clock: () -> Long,
        val nested: Rdf4jNestedQueries,
    )

    private val pending = ThreadLocal<Scope?>()

    /** Runs [block] with a scope that the next strategy created on this thread takes. */
    fun <T> with(
        functions: SparqlFunctionLibrary,
        depth: Int,
        deadline: Long,
        clock: () -> Long,
        nested: Rdf4jNestedQueries,
        block: () -> T,
    ): T {
        val previous = pending.get()
        pending.set(Scope(functions, depth, deadline, clock, nested))
        try {
            return block()
        } finally {
            if (previous == null) pending.remove() else pending.set(previous)
        }
    }

    /** The scope waiting for a strategy on this thread, if any. */
    val current: Scope? get() = pending.get()

    /** Takes the scope waiting on this thread: only the first strategy created within [with] evaluates its functions. */
    fun claim(): Scope? = pending.get()?.also { pending.set(null) }
}

/** A nested query of a custom function, prepared by the repository the calling query runs on. */
internal class Rdf4jNestedQuery(
    val parsed: ParsedQuery,
    /** The dataset to evaluate [parsed] on (Kastor's dataset rules); null for the union of every context. */
    val dataset: Dataset?,
    /** Bound natively: variables that stand for constants the query text cannot spell. */
    val bindings: org.eclipse.rdf4j.query.BindingSet,
    /** Variables that are not returned in rows. */
    val hidden: Set<String>,
)

/** Prepares the nested queries of custom functions as the repository prepares its own sandboxed queries. */
internal fun interface Rdf4jNestedQueries {
    /**
     * Applies [bindings] to [query] (the initial-bindings contract of a top-level query), refuses it with
     * [IllegalArgumentException] as a top-level sandboxed query is refused, and parses it.
     */
    fun prepare(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>): Rdf4jNestedQuery
}

/** A [MemoryStore] whose queries use [KastorEvaluationStrategyFactory]. */
internal fun kastorMemoryStore(): MemoryStore = MemoryStore().apply {
    evaluationStrategyFactory = KastorEvaluationStrategyFactory(federatedServiceResolver, QueryEvaluationMode.STANDARD)
}

/** A [NativeStore] whose queries use [KastorEvaluationStrategyFactory] (strict, as its default factory is). */
internal fun kastorNativeStore(location: String): NativeStore = NativeStore(java.io.File(location)).apply {
    evaluationStrategyFactory = KastorEvaluationStrategyFactory(federatedServiceResolver, QueryEvaluationMode.STRICT)
}

/**
 * The factory a Kastor store creates its strategies with: RDF4J's own (default or strict) evaluation, plus the custom
 * functions of the sandboxed query being evaluated, if any ([Rdf4jFunctionScope]). An inferencer or SHACL sail that
 * wraps the store evaluates queries with the store's connection, so with this factory too.
 */
internal class KastorEvaluationStrategyFactory(
    resolver: FederatedServiceResolver?,
    private val mode: QueryEvaluationMode,
) : DefaultEvaluationStrategyFactory(resolver) {

    override fun createEvaluationStrategy(dataset: Dataset?, tripleSource: TripleSource, statistics: EvaluationStatistics): EvaluationStrategy {
        val strategy = KastorEvaluationStrategy(
            tripleSource, dataset, federatedServiceResolver, querySolutionCacheThreshold, statistics, isTrackResultSize, mode,
            Rdf4jFunctionScope.claim(),
        )
        optimizerPipeline.ifPresent(strategy::setOptimizerPipeline)
        return strategy
    }
}

/**
 * RDF4J's evaluation, plus the custom functions of [scope] (null: an ordinary query, evaluated exactly as RDF4J does).
 *
 * For a sandboxed query (a [scope]):
 * - a function IRI that RDF4J's `FunctionRegistry` knows is evaluated by RDF4J: a built-in wins over a custom function
 *   (SHACL 1.2 SPARQL Extensions, section 7.3);
 * - otherwise a custom function of [scope] is evaluated with its arguments ([SparqlFunction.bindArguments]); every
 *   failure is a SPARQL error ([ValueExprEvaluationException]: `BIND` leaves the variable unbound, `FILTER` is false),
 *   except an interrupted nested query ([QueryInterruptedException]), which stops this query;
 * - any other function IRI is an error when it is called, as on Jena (RDF4J fails the whole query otherwise);
 * - calls with constant arguments are not folded while the query is optimized, so that each call runs its body once
 *   and RDF4J does not fail on an unknown function before evaluation.
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

    override fun optimize(expr: TupleExpr, evaluationStatistics: EvaluationStatistics, bindings: org.eclipse.rdf4j.query.BindingSet): TupleExpr {
        if (scope != null && callsCustomFunction(expr)) {
            val standard = StandardQueryOptimizerPipeline(this, tripleSource, evaluationStatistics)
            setOptimizerPipeline { standard.optimizers.filterNot { it is ConstantOptimizer } }
        }
        return super.optimize(expr, evaluationStatistics, bindings)
    }

    override fun prepare(node: FunctionCall, context: QueryEvaluationContext): QueryValueEvaluationStep {
        val iri = node.uri
        if (scope == null || isBuiltIn(iri)) return super.prepare(node, context)
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
        } catch (e: ValueExprEvaluationException) {
            throw e
        } catch (e: Exception) {
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<QueryInterruptedException>().firstOrNull()?.let { throw it }
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
            if (scope.depth >= MAX_FUNCTION_DEPTH) throw ValueExprEvaluationException("Custom SPARQL functions nest deeper than $MAX_FUNCTION_DEPTH")
            val nested = scope.nested.prepare(query, bindings)
            val deadline = scope.deadline
            fun checkDeadline() {
                if (deadline != Long.MAX_VALUE && scope.clock() - deadline >= 0) {
                    throw QueryInterruptedException("A nested query of a custom SPARQL function ran out of time")
                }
            }
            checkDeadline()
            val strategy = KastorEvaluationStrategy(
                tripleSource, nested.dataset, serviceResolver, cacheThreshold, statistics, trackResults, queryEvaluationMode,
                Rdf4jFunctionScope.Scope(query.functions, scope.depth + 1, deadline, scope.clock, scope.nested),
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
        fun isBuiltIn(iri: String) = FunctionRegistry.getInstance().has(iri)

        /** Whether [expr] calls a function that RDF4J's registry does not know. */
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
