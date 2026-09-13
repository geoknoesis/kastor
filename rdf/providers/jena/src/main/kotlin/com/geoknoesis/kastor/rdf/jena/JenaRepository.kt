package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.ReadWrite
import org.apache.jena.rdf.model.InfModel
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.tdb2.TDB2Factory
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Transactional Jena store. Inference is a read view and never replaces asserted data.
 *
 * **Inference views** (`*-inference` variants) are RDFS inference models built once per graph and
 * cached until the next committed write, instead of per query. Jena inference graphs are not safe
 * for concurrent use, so reads on an inference repository are serialized by a repository lock
 * (plain repositories keep fully concurrent reads).
 *
 * **Query errors:** failures while preparing or evaluating a query surface as [RdfQueryException];
 * exceptions thrown by a caller-supplied `consume` lambda propagate unchanged.
 */
class JenaRepository private constructor(
    private val dataset: Dataset,
    internal val inference: Boolean = false,
    private val variantId: String = if (inference) "memory-inference" else "memory",
) : RdfRepository {
    private val closed = AtomicBoolean(false)

    companion object {
        fun MemoryRepository(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem())
        fun MemoryRepositoryWithInference(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem(), true)
        fun Tdb2Repository(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), false, "tdb2")
        fun Tdb2RepositoryWithInference(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), true, "tdb2-inference")

        private const val DEFAULT_GRAPH_KEY = ""
    }

    /** Bumped around every committed write; cached inference models from older generations are stale. */
    private val generation = AtomicLong()
    private class CachedInference(val generation: Long, val model: InfModel)
    private val inferenceCache = ConcurrentHashMap<String, CachedInference>()
    private val inferenceLock = ReentrantLock()

    internal fun <T> withRead(block: () -> T): T =
        if (inference) inferenceLock.withLock { inTransaction(ReadWrite.READ, block) }
        else inTransaction(ReadWrite.READ, block)

    internal fun <T> withWrite(block: () -> T): T = inTransaction(ReadWrite.WRITE, block)

    private fun <T> inTransaction(mode: ReadWrite, block: () -> T): T {
        check(!closed.get()) { "Repository is closed" }
        if (dataset.isInTransaction) {
            check(mode != ReadWrite.WRITE || dataset.transactionMode() == ReadWrite.WRITE) { "Cannot write inside a read transaction" }
            return block()
        }
        dataset.begin(mode)
        try {
            val result = block()
            if (mode == ReadWrite.WRITE) {
                // Invalidate before and after the commit: a reader that builds an inference
                // model in between may still see the pre-commit snapshot.
                invalidateInference()
                dataset.commit()
                invalidateInference()
            }
            return result
        } catch (e: Throwable) {
            if (mode == ReadWrite.WRITE) dataset.abort()
            throw e
        } finally { dataset.end() }
    }

    private fun invalidateInference() {
        if (!inference) return
        generation.incrementAndGet()
        inferenceCache.clear()
    }

    /**
     * Read view of [model] (identified by [graphKey]). Must be called inside a transaction.
     * Inference views are cached per graph until the next committed write; inside a write
     * transaction a fresh, uncached view is built so uncommitted changes are visible.
     */
    internal fun readModel(graphKey: String, model: Model): Model {
        if (!inference) return model
        if (dataset.transactionMode() == ReadWrite.WRITE) return ModelFactory.createRDFSModel(model)
        val current = generation.get()
        inferenceCache[graphKey]?.takeIf { it.generation == current }?.let { return it.model }
        return ModelFactory.createRDFSModel(model).also { inferenceCache[graphKey] = CachedInference(current, it) }
    }

    /** Dataset used for queries and dataset serialization: the store, or its inference view. Call inside a transaction. */
    internal fun queryDataset(): Dataset {
        if (!inference) return dataset
        // Facade models borrow the store; closing them would close the borrowed models.
        return DatasetFactory.create(readModel(DEFAULT_GRAPH_KEY, dataset.defaultModel)).also { view ->
            dataset.listNames().forEachRemaining { view.addNamedModel(it, readModel(it, dataset.getNamedModel(it))) }
        }
    }

    private val defaultGraphView by lazy { JenaGraph(dataset.defaultModel, this, DEFAULT_GRAPH_KEY) }
    override val defaultGraph: RdfGraph get() = withRead { defaultGraphView }
    override fun getGraph(name: Iri): RdfGraph = withRead { JenaGraph(dataset.getNamedModel(name.value), this, name.value) }
    override fun hasGraph(name: Iri): Boolean = withRead { !dataset.getNamedModel(name.value).isEmpty }
    override fun listGraphs(): List<Iri> = withRead { dataset.listNames().asSequence().map(::Iri).toList() }
    override fun createGraph(name: Iri): RdfGraph = getGraph(name)
    override fun removeGraph(name: Iri): Boolean = withWrite {
        val existed = dataset.containsNamedModel(name.value)
        dataset.removeNamedModel(name.value)
        existed
    }
    override fun editDefaultGraph(): MutableRdfGraph = defaultGraph as MutableRdfGraph
    override fun editGraph(name: Iri): MutableRdfGraph = getGraph(name) as MutableRdfGraph

    override fun select(query: SparqlSelect): SparqlQueryResult = withSelectRows(query) { JenaResultSet(it.toList()) }

    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) { QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()) }
        exec.use { consumeRows(it, query.sparql, consume) }
    }

    override fun ask(query: SparqlAsk): Boolean = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { it.execAsk() }
        }
    }

    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) {
            val initial = org.apache.jena.query.QuerySolutionMap()
            bindings.forEach { (name, term) -> initial.add(name, ModelFactory.createDefaultModel().asRDFNode(JenaTerms.toJenaNode(term))) }
            QueryExecution.dataset(queryDataset()).query(query.sparql).substitution(initial)
                .timeout(timeout.toMillis().coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }
        exec.use { consumeRows(it, query.sparql, consume) }
    }

    private fun <T> consumeRows(exec: QueryExecution, sparql: String, consume: (Sequence<BindingSet>) -> T): T {
        val results = queryOperation(sparql) { exec.execSelect() }
        val rows = results.asSequence().map { row ->
            MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) }) as BindingSet
        }
        return consume(rows.guardedBy(sparql))
    }

    override fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T = withRead {
        val exec = queryOperation(query.sparql) { QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()) }
        exec.use {
            val triples = queryOperation(query.sparql) { it.execConstructTriples() }
            consume(triples.asSequence().map(JenaTerms::fromJenaTriple).guardedBy(query.sparql))
        }
    }

    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = withConstructTriples(query) { it.toList().asSequence() }

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { exec ->
                val model = exec.execDescribe()
                try { JenaGraph(model).getTriples().asSequence() } finally { model.close() }
            }
        }
    }

    override fun update(query: UpdateQuery): Unit = withWrite {
        queryOperation(query.sparql) { org.apache.jena.update.UpdateAction.parseExecute(query.sparql, dataset) }
    }

    override fun transaction(operations: RdfRepository.() -> Unit): Unit = withWrite { operations(this) }
    override fun readTransaction(operations: RdfRepository.() -> Unit): Unit = withRead { operations(this) }

    override fun clear(): Boolean = withWrite {
        val hadData = !dataset.isEmpty
        dataset.defaultModel.removeAll()
        dataset.listNames().asSequence().toList().forEach { dataset.removeNamedModel(it) }
        hadData
    }

    override fun isClosed(): Boolean = closed.get()

    override fun close() {
        check(!dataset.isInTransaction) { "Cannot close inside a transaction" }
        if (closed.compareAndSet(false, true)) {
            inferenceCache.clear()
            dataset.close()
        }
    }

    override fun getCapabilities(): ProviderCapabilities = JenaProvider().getCapabilities(variantId)

    internal fun getJenaDataset(): Dataset = dataset

    /** Wraps failures of the query engine itself; never used around caller-supplied consumers. */
    private inline fun <T> queryOperation(query: String, operation: () -> T): T = try {
        operation()
    } catch (e: RdfException) {
        throw e
    } catch (e: Exception) {
        throw RdfQueryException("SPARQL execution failed: ${e.message}", query = query, cause = e)
    }

    /**
     * Wraps engine failures raised while *iterating* results (e.g. timeouts, evaluation errors) as
     * [RdfQueryException], while exceptions thrown by the consumer's own code are left untouched.
     */
    private fun <T> Sequence<T>.guardedBy(query: String): Sequence<T> {
        val source = this
        return Sequence {
            val iterator = queryOperation(query) { source.iterator() }
            object : Iterator<T> {
                override fun hasNext(): Boolean = queryOperation(query) { iterator.hasNext() }
                override fun next(): T = queryOperation(query) { iterator.next() }
            }
        }.constrainOnce()
    }
}
