package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * Graph view over a [SparqlRepository] (or any [SparqlMutable]).
 *
 * ## Blank nodes
 * Blank-node labels in SPARQL Update are scoped to a single request, so:
 * - [addTriple]/[addTriples] accept blank nodes. Triples connected through blank nodes form a
 *   component that is always sent in one request (never split); components and triples without
 *   blank nodes are packed into requests of up to [SparqlEndpointConfig.insertBatchSize] triples (a
 *   larger component is sent alone). Components larger than
 *   [SparqlEndpointConfig.maxBlankNodeComponentTriples] are rejected before anything is sent. Labels
 *   are re-issued per request, so every call creates fresh blank nodes on the endpoint.
 *   Endpoint-assigned identifiers read back from results (for example Virtuoso's `nodeID://b1`) can
 *   therefore be copied into another graph.
 * - [hasTriple], [find], [removeTriple] and [removeTriples] cannot address an existing blank node
 *   by label and throw [IllegalArgumentException]; use an explicit `DELETE WHERE` pattern instead.
 * - Blank nodes read from results keep the label the endpoint sent ([BlankNode] of that label). The
 *   SPARQL results formats scope such labels to one result set, and most stores (Jena Fuseki, RDF4J,
 *   GraphDB) number them per response (`b0`, `b1`, ...): the same label in the results of two calls
 *   is in general **not** the same node, yet the two [BlankNode]s compare equal. Compare or join
 *   blank nodes only within the rows of one call (one [find], [getTriples] or `select`). Only stores
 *   that send stable identifiers (Virtuoso's `nodeID://...`, or stores configured for skolem IRIs)
 *   make labels comparable across calls.
 *
 * ## Reads
 * [find] and [getTriples] are one buffered SELECT each, capped by [SparqlEndpointConfig.maxResponseBytes], and
 * [getTriplesSequence] reads the same list: a lazily streamed sequence could not release its HTTP response when the
 * caller stops iterating early. Stream a graph larger than that cap with [SparqlRepository.withSelectRows], which
 * is capped by [SparqlEndpointConfig.maxStreamedResponseBytes] and closes the response when its consumer returns.
 * [find] and [getTriples] check every row the endpoint returns: a row that is not a triple (a
 * variable left unbound, a literal as subject, anything but an IRI as predicate) fails with
 * [RdfQueryException]. With [MalformedTermPolicy.SKIP_ROW] rows with malformed terms are left out,
 * while [size] is counted by the endpoint and still includes them.
 *
 * ## Batched writes are not atomic
 * [addTriples] and [removeTriples] send one request per batch. Every triple is validated and
 * rendered before the first request, so an invalid term never leaves earlier batches applied. A
 * transport or server failure after earlier batches succeeded cannot be undone; it is reported as
 * an [RdfQueryException] that states how many triples the earlier requests wrote.
 */
class SparqlGraph(
    private val repository: SparqlMutable,
    private val graphName: Iri? = null
) : MutableRdfGraph, SourceTrackedGraph {

    override val sourceRepository: RdfRepository?
        get() = repository as? RdfRepository
    override val sourceGraphName: Iri? = graphName

    private val endpointConfig: SparqlEndpointConfig?
        get() = (repository as? SparqlRepository)?.config

    private val batchSize: Int
        get() = endpointConfig?.insertBatchSize ?: SparqlEndpointConfig.DEFAULT_INSERT_BATCH_SIZE

    private fun pattern(body: String): String =
        if (graphName == null) body else "GRAPH ${SparqlTermFormat.iriRef(graphName.value)} { $body }"
    private fun rendered(t: RdfTriple, blankNode: (BlankNode) -> String): String =
        "${SparqlTermFormat.term(t.subject, blankNode)} ${SparqlTermFormat.iriRef(t.predicate.value)} ${SparqlTermFormat.term(t.obj, blankNode)} ."

    /** Same as `addTriples(listOf(triple))`. */
    override fun addTriple(triple: RdfTriple) {
        addTriples(listOf(triple))
    }

    /** `INSERT DATA` in batches; see the class documentation for blank nodes. */
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        val maxComponent = endpointConfig?.maxBlankNodeComponentTriples ?: SparqlEndpointConfig.DEFAULT_MAX_BLANK_NODE_COMPONENT_TRIPLES
        val units = blankNodeComponents(triples)
        units.firstOrNull { it.size > maxComponent }?.let { component ->
            throw IllegalArgumentException(
                "${component.size} triples are connected through blank nodes and must be sent in one request, " +
                    "which exceeds maxBlankNodeComponentTriples ($maxComponent); nothing was inserted"
            )
        }
        validate(triples) { "_:b" }
        var written = 0
        val batch = ArrayList<RdfTriple>()
        fun send() {
            reportingProgress("INSERT DATA", written, triples.size) { insertData(batch) }
            written += batch.size
            batch.clear()
        }
        for (unit in units) {
            if (batch.isNotEmpty() && batch.size + unit.size > batchSize) send()
            batch.addAll(unit)
        }
        if (batch.isNotEmpty()) send()
    }

    /** Render every triple (and the graph name) once, so invalid terms fail before any request. */
    private fun validate(triples: Collection<RdfTriple>, blankNode: (BlankNode) -> String) {
        pattern("")
        triples.forEach { rendered(it, blankNode) }
    }

    /** Run one batch request; a failure after earlier batches succeeded says how much was written. */
    private inline fun reportingProgress(operation: String, written: Int, total: Int, request: () -> Unit) {
        try {
            request()
        } catch (e: RdfQueryException) {
            if (written == 0) throw e
            throw RdfQueryException(
                "SPARQL $operation failed after earlier requests had written $written of $total triples: ${e.message}",
                query = e.query,
                cause = e,
            )
        }
    }

    /**
     * Groups [triples] into units that must travel together: each connected component of triples
     * sharing blank nodes, and each triple without blank nodes on its own. Order of first occurrence
     * is kept.
     */
    private fun blankNodeComponents(triples: Collection<RdfTriple>): List<List<RdfTriple>> {
        val parent = HashMap<String, String>()
        fun find(id: String): String {
            var root = id
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var node = id
            while (parent.getValue(node) != root) {
                val next = parent.getValue(node)
                parent[node] = root
                node = next
            }
            return root
        }
        fun blankIds(t: RdfTriple) = listOfNotNull((t.subject as? BlankNode)?.id, (t.obj as? BlankNode)?.id)
        for (t in triples) {
            val ids = blankIds(t)
            ids.forEach { parent.putIfAbsent(it, it) }
            if (ids.size == 2) {
                val a = find(ids[0])
                val b = find(ids[1])
                if (a != b) parent[b] = a
            }
        }
        val components = LinkedHashMap<Any, MutableList<RdfTriple>>()
        for (t in triples) {
            val ids = blankIds(t)
            val key: Any = if (ids.isEmpty()) Any() else find(ids[0])
            components.getOrPut(key) { ArrayList() }.add(t)
        }
        return components.values.toList()
    }

    private fun insertData(triples: Collection<RdfTriple>) {
        val labels = HashMap<String, String>()
        val body = triples.joinToString("\n") { t -> rendered(t) { node -> "_:" + labels.getOrPut(node.id) { "b${labels.size}" } } }
        repository.update(UpdateQuery("INSERT DATA { ${pattern(body)} }"))
    }

    override fun removeTriple(triple: RdfTriple): Boolean = removeTriples(listOf(triple))

    /**
     * `DELETE DATA` in batches of [SparqlEndpointConfig.insertBatchSize]. Returns `true` if at least
     * one triple existed before deletion; this is determined with a single `ASK ... VALUES` per batch
     * (skipped once a match is known) rather than one request per triple. The check and the delete
     * are separate requests, so concurrent writers can race with them: the result is best-effort.
     */
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        if (triples.isEmpty()) return false
        validate(triples, ::rejectBlankNode)
        var existed = false
        var written = 0
        triples.chunked(batchSize).forEach { batch ->
            val body = batch.joinToString("\n") { rendered(it, ::rejectBlankNode) }
            reportingProgress("DELETE DATA", written, triples.size) {
                if (!existed) existed = anyExists(batch)
                repository.update(UpdateQuery("DELETE DATA { ${pattern(body)} }"))
            }
            written += batch.size
        }
        return existed
    }

    private fun anyExists(batch: List<RdfTriple>): Boolean {
        val rows = batch.joinToString(" ") { t ->
            "(${SparqlTermFormat.term(t.subject, ::rejectBlankNode)} ${SparqlTermFormat.iriRef(t.predicate.value)} " +
                "${SparqlTermFormat.term(t.obj, ::rejectBlankNode)})"
        }
        return repository.ask(SparqlAskQuery("ASK { VALUES (?s ?p ?o) { $rows } ${pattern("?s ?p ?o")} }"))
    }

    override fun hasTriple(triple: RdfTriple): Boolean {
        val body = "${SparqlTermFormat.term(triple.subject, ::rejectBlankNode)} ${SparqlTermFormat.iriRef(triple.predicate.value)} " +
            SparqlTermFormat.term(triple.obj, ::rejectBlankNode)
        return repository.ask(SparqlAskQuery("ASK { ${pattern(body)} }"))
    }

    override fun getTriples(): List<RdfTriple> = find()
    fun getTriples(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> = find(subject, predicate, obj)
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
        val body = "${subject?.let { SparqlTermFormat.term(it, ::rejectBlankNode) } ?: "?s"} " +
            "${predicate?.let { SparqlTermFormat.iriRef(it.value) } ?: "?p"} " +
            "${obj?.let { SparqlTermFormat.term(it, ::rejectBlankNode) } ?: "?o"} ."
        val query = "SELECT * WHERE { ${pattern(body)} }"
        val result = repository.select(SparqlSelectQuery(query))
        // The endpoint is not trusted to answer with triples: a row that is none is reported, not cast.
        fun notATriple(variable: String, term: RdfTerm?, expected: String): Nothing = throw RdfQueryException(
            "SPARQL endpoint returned a row that is not a triple: ?$variable is " +
                when (term) {
                    null -> "unbound"
                    is Literal -> "a literal, not $expected"
                    is BlankNode -> "a blank node, not $expected"
                    is Iri -> "an IRI, not $expected"
                    else -> "a ${term.javaClass.simpleName}, not $expected"
                },
            query = query,
        )
        return result.map { row ->
            val s = subject ?: row.get("s").let { it as? RdfResource ?: notATriple("s", it, "an IRI or a blank node") }
            val p = predicate ?: row.get("p").let { it as? Iri ?: notATriple("p", it, "an IRI") }
            val o = obj ?: row.get("o") ?: notATriple("o", null, "an RDF term")
            RdfTriple(s, p, o)
        }
    }

    override fun clear(): Boolean {
        val existed = repository.ask(SparqlAskQuery("ASK { ${pattern("?s ?p ?o")} }"))
        repository.update(UpdateQuery(if (graphName == null) "CLEAR DEFAULT" else "CLEAR SILENT GRAPH ${SparqlTermFormat.iriRef(graphName.value)}"))
        return existed
    }

    /**
     * Number of triples, counted by the endpoint. Throws [RdfQueryException] when the count is not an
     * integer or does not fit in an [Int].
     */
    override fun size(): Int {
        val query = "SELECT (COUNT(*) AS ?count) WHERE { ${pattern("?s ?p ?o")} }"
        val result = repository.select(SparqlSelectQuery(query))
        val term = result.firstOrNull()?.get("count") as? Literal ?: throw RdfQueryException(
            "SPARQL endpoint returned a missing or non-literal triple count",
            query = query,
        )
        val count = term.lexical.trim().toLongOrNull()
        if (count == null || count < 0 || count > Int.MAX_VALUE) {
            throw RdfQueryException("SPARQL endpoint returned a triple count of '${term.lexical}', which is not a valid Int size", query = query)
        }
        return count.toInt()
    }

    private fun rejectBlankNode(node: BlankNode): String = throw IllegalArgumentException(
        "Blank node '${node.id}' cannot be addressed over SPARQL: blank-node labels are scoped to one request " +
            "and endpoint-assigned identifiers are not valid query constants; use an explicit DELETE WHERE pattern instead"
    )
}
