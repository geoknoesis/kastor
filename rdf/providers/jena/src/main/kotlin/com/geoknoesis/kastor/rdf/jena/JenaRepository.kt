package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.ReadWrite
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.tdb2.TDB2Factory
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Transactional Jena store. Inference is a read view and never replaces asserted data. */
class JenaRepository private constructor(private val dataset: Dataset, internal val inference: Boolean = false) : RdfRepository {
    private val closed = AtomicBoolean(false)
    companion object {
        fun MemoryRepository(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem())
        fun MemoryRepositoryWithInference(): JenaRepository = JenaRepository(DatasetFactory.createTxnMem(), true)
        fun Tdb2Repository(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()))
        fun Tdb2RepositoryWithInference(location: String): JenaRepository =
            JenaRepository(TDB2Factory.connectDataset(Paths.get(location).toAbsolutePath().toString()), true)
    }
    internal fun <T> withRead(block: () -> T): T = inTransaction(ReadWrite.READ, block)
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
            if (mode == ReadWrite.WRITE) dataset.commit()
            return result
        } catch (e: Throwable) {
            if (mode == ReadWrite.WRITE) dataset.abort()
            throw e
        } finally { dataset.end() }
    }
    internal fun readModel(model: Model): Model = if (inference) ModelFactory.createRDFSModel(model) else model
    private fun queryDataset(): Dataset {
        if (!inference) return dataset
        // Facade models borrow the store; closing them would close the borrowed models.
        return DatasetFactory.create(readModel(dataset.defaultModel)).also { view ->
            dataset.listNames().forEachRemaining { view.addNamedModel(it, readModel(dataset.getNamedModel(it))) }
        }
    }
    private val defaultGraphView by lazy { withRead { JenaGraph(dataset.defaultModel, this) } }
    override val defaultGraph: RdfGraph get() = withRead { defaultGraphView }
    override fun getGraph(name: Iri): RdfGraph = withRead { JenaGraph(dataset.getNamedModel(name.value), this) }
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
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { exec ->
                consume(exec.execSelect().asSequence().map { row ->
                    MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) })
                })
            }
        }
    }
    override fun ask(query: SparqlAsk): Boolean = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { it.execAsk() }
        }
    }
    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T = withRead {
        val model = ModelFactory.createDefaultModel()
        try {
            val initial = org.apache.jena.query.QuerySolutionMap()
            bindings.forEach { (name, term) -> initial.add(name, JenaTerms.toNode(model, term)) }
            org.apache.jena.query.QueryExecution.dataset(queryDataset()).query(query.sparql).substitution(initial)
                .timeout(timeout.toMillis().coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS).build().use { exec ->
                    consume(exec.execSelect().asSequence().map { row ->
                        MapBindingSet(row.varNames().asSequence().associateWith { JenaTerms.fromNode(row.get(it)) })
                    })
                }
        } finally { model.close() }
    }
    override fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T = withRead {
        queryOperation(query.sparql) {
            QueryExecutionFactory.create(QueryFactory.create(query.sparql), queryDataset()).use { exec ->
                val model = ModelFactory.createDefaultModel()
                try {
                    consume(exec.execConstructTriples().asSequence().map {
                        RdfTriple(JenaTerms.fromNode(model.asRDFNode(it.subject)) as RdfResource,
                            Iri(it.predicate.uri), JenaTerms.fromNode(model.asRDFNode(it.`object`)))
                    })
                } finally { model.close() }
            }
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
        if (closed.compareAndSet(false, true)) dataset.close()
    }
    override fun getCapabilities(): ProviderCapabilities = JenaProvider().getCapabilities(if (inference) "memory-inference" else "memory")
    internal fun getJenaDataset(): Dataset = dataset
    private inline fun <T> queryOperation(query: String, operation: () -> T): T = try {
        operation()
    } catch (e: RdfQueryException) { throw e
    } catch (e: Exception) {
        throw RdfQueryException("SPARQL execution failed: ${e.message}", query = query, cause = e)
    }
}
