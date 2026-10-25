package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionCall
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryCancelledException
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.Query
import org.apache.jena.query.QuerySolutionMap
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.expr.ExprEvalException
import org.apache.jena.sparql.expr.ExprList
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.Function
import org.apache.jena.sparql.function.FunctionEnv
import org.apache.jena.sparql.util.Context
import java.util.concurrent.TimeUnit

/**
 * A custom SPARQL [function] as Jena evaluates it in a sandboxed query at nesting [depth] (0 for the query the caller
 * runs). It is made visible to that one execution by [JenaQuerySandbox.restrict], never through the global registry.
 *
 * Every failure is a SPARQL error ([ExprEvalException]): an argument error the parameters do not allow
 * ([SparqlFunction.bindArguments]), a body that returns null or throws, and a nested query that is refused, that
 * cannot be parsed or that nests deeper than [SparqlFunction.MAX_FUNCTION_DEPTH]. Only a cancellation
 * ([QueryCancelledException], also for a body interrupted with [InterruptedException], whose interrupt flag is
 * restored) and an exceeded sandbox limit ([SandboxLimitException], fail closed) stop the calling query.
 *
 * Nested queries run on the dataset of the calling query, restricted like it, and end by [deadline] (a [clock] value;
 * [Long.MAX_VALUE] when the calling query has no timeout). Jena creates one instance per call site and execution, so
 * a nested query is validated, parsed and checked once per instance ([prepared], a bounded cache), not once per row:
 * the bindings of a call are applied with `substitution`, never written into the text.
 */
internal class JenaLibraryFunction(
    private val function: SparqlFunction,
    private val depth: Int,
    private val deadline: Long,
    private val clock: () -> Long,
) : Function {

    override fun build(uri: String, args: ExprList, context: Context) = Unit

    internal companion object {
        /** Most nested-query parses one call site keeps, as on RDF4J (`Rdf4jQuerySandbox.NestedParses`). */
        const val NESTED_PARSES: Int = 64
    }

    /** What a nested query's preparation depends on: its text and which variables are bound, to what kind of term. */
    private data class NestedKey(val sparql: String, val bound: Set<String>, val literals: Set<String>)

    /**
     * The checked parses of the nested queries of this call site, at most [NESTED_PARSES] of them (the least recently
     * used dropped first), so a body that builds its query text from data does not grow it for the whole execution;
     * a refused or malformed query is never kept.
     */
    private val prepared = object : LinkedHashMap<NestedKey, Query>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NestedKey, Query>?): Boolean = size > NESTED_PARSES
    }

    /** How many nested-query parses this call site keeps (for tests). */
    internal fun cachedParses(): Int = synchronized(prepared) { prepared.size }

    /**
     * [query] validated against the initial-bindings contract of a top-level query (see JenaRepository.withSelectRows)
     * for [bindings], parsed as sandboxed SPARQL and checked, once per [NestedKey].
     */
    internal fun prepare(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>): Query {
        val key = NestedKey(query.sparql, bindings.keys, bindings.filterValues { it is Literal || it is TripleTerm }.keys)
        synchronized(prepared) { prepared[key] }?.let { return it }
        SparqlInitialBindings.validate(key.sparql, key.bound, key.literals)
        val parsed = JenaQuerySandbox.parseChecked(key.sparql)
        synchronized(prepared) { prepared[key] = parsed }
        return parsed
    }

    override fun exec(binding: Binding, args: ExprList, uri: String, env: FunctionEnv): NodeValue {
        val evaluated = args.list.map { expr ->
            try {
                JenaTerms.fromJenaNode(expr.eval(binding, env).asNode())
            } catch (e: ExprEvalException) {
                null
            } catch (e: IllegalArgumentException) {
                null // a node Kastor cannot represent
            }
        }
        val arguments = function.bindArguments(evaluated)
            ?: throw ExprEvalException("Bad arguments for the custom function <$uri>")
        val result = try {
            function.body(Call(arguments, env))
        } catch (e: QueryCancelledException) {
            throw e
        } catch (e: SandboxLimitException) {
            throw e
        } catch (e: ExprEvalException) {
            throw e
        } catch (e: InterruptedException) {
            // The thread was asked to stop: keep the request and stop the calling query.
            Thread.currentThread().interrupt()
            throw QueryCancelledException(e)
        } catch (e: Exception) {
            // A cancellation or an exceeded sandbox limit of a nested query stops the calling query too.
            generateSequence<Throwable>(e) { it.cause }
                .firstOrNull { it is QueryCancelledException || it is SandboxLimitException }?.let { throw it }
            throw ExprEvalException("The custom function <$uri> failed: ${e.message}", e)
        } ?: throw ExprEvalException("The custom function <$uri> has no result")
        return try {
            NodeValue.makeNode(JenaTerms.toJenaNode(result))
        } catch (e: IllegalArgumentException) {
            throw ExprEvalException("The custom function <$uri> returned ${e.message}", e)
        }
    }

    private inner class Call(override val arguments: List<RdfTerm?>, private val env: FunctionEnv) : SparqlFunctionCall {

        override fun <T> select(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>, consume: (Sequence<BindingSet>) -> T): T {
            if (depth >= SparqlFunction.MAX_FUNCTION_DEPTH) {
                throw ExprEvalException("Custom SPARQL functions nest deeper than ${SparqlFunction.MAX_FUNCTION_DEPTH}")
            }
            val parsed = prepare(query, bindings)
            val dataset = env.dataset ?: throw ExprEvalException("No dataset for a nested query")
            val builder = JenaQuerySandbox.restrict(
                QueryExecution.dataset(DatasetFactory.wrap(dataset)).query(parsed), query.functions, depth + 1, deadline, clock,
            )
            if (bindings.isNotEmpty()) {
                val initial = QuerySolutionMap()
                val model = ModelFactory.createDefaultModel()
                bindings.forEach { (name, term) -> initial.add(name, model.asRDFNode(JenaTerms.toJenaNode(term))) }
                builder.substitution(initial)
            }
            if (deadline != Long.MAX_VALUE) {
                val left = deadline - clock()
                if (left <= 0) throw QueryCancelledException()
                builder.timeout(TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
            return builder.build().use { exec ->
                val rows = exec.execSelect()
                JenaQuerySandbox.requireBounded(exec)
                consume(rows.asSequence().map { row ->
                    MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) }) as BindingSet
                })
            }
        }
    }
}
