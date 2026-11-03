package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import org.geoknoesis.lens.TabularLens
import org.geoknoesis.lens.TabularLensCompiler
import java.time.Duration

/**
 * Executes typed tabular views through Kastor's streaming SELECT provider seam.
 *
 * All joins, grouping and COUNT(DISTINCT) execute in the RDF provider, while
 * the final result is bounded and materialized for this initial API.
 * Caller remains responsible for authorized repositories and CPU/memory quotas.
 */
class KastorTabularLensExecutor(
    private val repository: RdfRepository,
    private val compiler: TabularLensCompiler = TabularLensCompiler()
) {
    data class Limits(
        val maxRows: Int = 1000,
        val timeout: Duration? = null
    ) {
        init {
            require(maxRows in 1..100_000) { "maxRows must be in 1..100000" }
            require(timeout == null || (!timeout.isNegative && !timeout.isZero)) {
                "timeout must be positive"
            }
        }
    }

    data class Table(
        val columns: List<String>,
        val rows: List<Map<String, RdfTerm?>>
    )

    /** View exact SPARQL and result schema, without running a query. */
    fun explain(lens: TabularLens): TabularLensCompiler.Compiled = compiler.compile(lens)

    /** Fail closed on overflow: never silently return a partial facet distribution. */
    fun execute(lens: TabularLens, limits: Limits = Limits()): Table {
        val compiled = compiler.compile(lens)
        val query = SparqlSelectQuery(compiled.sparql)
        fun consume(rows: Sequence<BindingSet>): Table {
            val bounded = rows.take(limits.maxRows + 1).map { row ->
                compiled.variables.associateWith { row.get(it) }
            }.toList()
            check(bounded.size <= limits.maxRows) {
                "Tabular lens result exceeds maxRows=${limits.maxRows}"
            }
            return Table(compiled.variables, bounded)
        }

        var result: Table? = null
        repository.readTransaction {
            result = if (limits.timeout == null) {
                withSelectRows(query) { rows -> consume(rows) }
            } else {
                withSelectRows(query, emptyMap(), limits.timeout) { rows -> consume(rows) }
            }
        }
        return checkNotNull(result)
    }
}
