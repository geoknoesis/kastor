package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import org.apache.jena.query.Dataset
import org.apache.jena.query.Query
import org.apache.jena.query.QueryExecutionDatasetBuilder
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.sparql.ARQConstants
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.exec.UpdateExecDatasetBuilder
import org.apache.jena.sparql.modify.request.UpdateLoad
import org.apache.jena.sparql.modify.request.UpdateModify
import org.apache.jena.sparql.syntax.Element
import org.apache.jena.sparql.util.Context
import org.apache.jena.sparql.util.Symbol
import org.apache.jena.update.Update
import org.apache.jena.update.UpdateException
import org.apache.jena.update.UpdateRequest

/**
 * Runs a SPARQL UPDATE on Jena with the bounds of Kastor's sandboxed queries on everything it evaluates, and through
 * Kastor's bounded parser for what it loads. Jena's own update engine evaluates the `WHERE` clause with no limit: a
 * short request could compute a literal of a million digits (`STRDT(CONCAT(...), xsd:integer)`, an XSD cast), whose
 * value Jena computes in time quadratic in its length (about 100 s), or a huge number (`POWER`, products), or a huge
 * string; and `LOAD` parsed its document with Jena's default parser, which values every numeric literal as it reads it.
 *
 * - **`WHERE` clauses** (of `INSERT`/`DELETE ... WHERE`) are checked as a sandboxed query is
 *   ([JenaQuerySandbox.check]: no `SERVICE`, no `CALL`, no ARQ extension functions or property paths, ...), and run
 *   with the sandbox's execution context ([JenaQuerySandbox.restrict]): every expression the engine evaluates, also
 *   inside `EXISTS`/`NOT EXISTS`, sub-queries, `GROUP BY`, `HAVING` and `ORDER BY`, is bounded by [JenaCost] and
 *   [com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits] before it computes (one registry for queries and
 *   updates), within the sandbox's memory budget; property functions are off and `SERVICE` has no executor. The
 *   request fails with `SandboxLimitException`'s message (reported as `RdfQueryException`) and its transaction rolls
 *   back. The `WHERE` clause has no deadline (an update has no timeout).
 * - **`LOAD`** reads its document as `parseDataset` does ([JenaParsing.parser]: the bounded node factory, strict
 *   syntax, Kastor's term checks, the JSON-LD check, no remote JSON-LD context), resolving against the source IRI.
 * - Every quad any operation writes is checked again by [JenaLiteralBounds.boundedWrites].
 */
internal object JenaUpdateBounds {
    /** False only when [text] certainly has no `WHERE` clause, so nothing to evaluate (a cheap look at the text). */
    fun mayCompute(text: String): Boolean = text.contains("WHERE", ignoreCase = true)

    /** Executes the operations of [request] on [dataset], in order, with the bounds above. */
    fun execute(request: UpdateRequest, dataset: Dataset) {
        val target = JenaLiteralBounds.boundedWrites(dataset).asDatasetGraph()
        for (operation in request.operations) {
            if (operation is UpdateLoad) load(operation, target) else evaluate(operation, target)
        }
    }

    private fun evaluate(operation: Update, target: DatasetGraph) {
        val pattern = wherePattern(operation)
        pattern?.let { JenaQuerySandbox.check(selectAll(it)) }
        val context = sandboxContext()
        UpdateExecDatasetBuilder.create().dataset(target).update(UpdateRequest().add(operation)).context(context).execute()
        val rewrite = context.get<Any>(ARQConstants.sysOptimizerFactory) as? JenaSandboxRewrite
        if (pattern != null && rewrite?.applied != true) {
            // The bounds run as the optimizer of the WHERE clause's query; refuse (and roll back) if it did not run.
            throw IllegalStateException("the WHERE clause of the update was evaluated without Kastor's bounds")
        }
    }

    /** The `WHERE` clause [operation] evaluates (`DELETE WHERE` has only a pattern of quads, with no expression). */
    private fun wherePattern(operation: Update): Element? = (operation as? UpdateModify)?.wherePattern

    private fun selectAll(pattern: Element): Query = Query().apply {
        setQuerySelectType()
        isQueryResultStar = true
        queryPattern = pattern
    }

    /** The execution context of a sandboxed query ([JenaQuerySandbox.restrict]), for one operation. */
    private fun sandboxContext(): Context {
        val context = org.apache.jena.query.ARQ.getContext().copy()
        JenaQuerySandbox.restrict(ContextCapture(context), SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime)
        return context
    }

    /** Collects what [JenaQuerySandbox.restrict] sets on a query execution into [context]. */
    private class ContextCapture(private val context: Context) : QueryExecutionDatasetBuilder() {
        override fun set(symbol: Symbol, value: Any): QueryExecutionDatasetBuilder = also { context.set(symbol, value) }
        override fun set(symbol: Symbol, value: Boolean): QueryExecutionDatasetBuilder = also { context.set(symbol, value) }
    }

    /** `LOAD <source> [INTO GRAPH <g>]` through Kastor's bounded parser; `SILENT` ignores any failure, as in Jena. */
    private fun load(operation: UpdateLoad, target: DatasetGraph) {
        try {
            val input = org.apache.jena.riot.system.streammgr.StreamManager.get().open(operation.source)
                ?: throw UpdateException("LOAD <${operation.source}>: the document cannot be opened")
            input.use {
                val lang = RDFDataMgr.determineLang(operation.source, it.contentType, null)
                    ?: throw UpdateException("LOAD <${operation.source}>: the syntax of the document is not known")
                val dest = operation.dest
                val sink = if (dest == null) {
                    StreamRDFLib.dataset(target)
                } else {
                    if (!RDFLanguages.isTriples(lang)) throw UpdateException("LOAD <${operation.source}>: cannot load quads into a graph")
                    StreamRDFLib.graph(target.getGraph(dest))
                }
                JenaParsing.parser(it, lang, operation.source).parse(JenaParsing.validating(sink))
            }
        } catch (e: Exception) {
            if (!operation.isSilent) throw e
        }
    }
}
