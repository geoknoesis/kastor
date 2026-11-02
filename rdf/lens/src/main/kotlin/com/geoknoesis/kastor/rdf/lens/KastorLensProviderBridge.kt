package com.geoknoesis.kastor.rdf.lens
import com.geoknoesis.kastor.rdf.*
import java.time.Duration

/** A bounded, resource-scoped provider seam for SELECT queries compiled by Semantic Lens. */
class KastorLensProviderBridge(private val repository: RdfRepository) {
    data class Edge(val subject: Iri, val predicate: Iri, val obj: Iri)
    data class Graph(val nodes: Set<Iri>, val edges: Set<Edge>)
    data class Limits(val maxNodes: Int = 5000, val maxEdges: Int = 15000, val timeout: Duration? = null) {
        init {
            require(maxNodes in 1 until Int.MAX_VALUE && maxEdges in 1 until Int.MAX_VALUE)
            require(timeout == null || (!timeout.isNegative && !timeout.isZero))
        }
    }

    fun nodes(sparql: String, limits: Limits = Limits()): Set<Iri> {
        var answer: Set<Iri>? = null
        repository.readTransaction { answer = readNodes(sparql, limits.maxNodes, limits.timeout) }
        return checkNotNull(answer)
    }

    /** Node and edge queries share one Kastor read transaction. Never calls eager select(). */
    fun network(nodeQuery: String, edgeQuery: String, limits: Limits = Limits()): Graph {
        var answer: Graph? = null
        repository.readTransaction {
            val nodes = readNodes(nodeQuery, limits.maxNodes, limits.timeout)
            val edges = if (nodes.isEmpty()) emptySet() else readEdges(edgeQuery, limits.maxEdges, limits.timeout)
            check(edges.all { it.subject in nodes && it.obj in nodes }) { "Edge endpoint outside selected nodes" }
            answer = Graph(nodes, edges)
        }
        return checkNotNull(answer)
    }

    private fun iri(row: BindingSet, name: String): Iri =
        (row.get(name) as? Iri) ?: error("Expected IRI binding for $name")

    private fun <T> rows(query: String, timeout: Duration?, consume: (Sequence<BindingSet>) -> T): T =
        if (timeout == null) repository.withSelectRows(SparqlSelectQuery(query), consume)
        else repository.withSelectRows(SparqlSelectQuery(query), emptyMap(), timeout, consume)

    private fun readNodes(query: String, max: Int, timeout: Duration?): Set<Iri> =
        rows(query, timeout) { bindings ->
            val result = bindings.take(max + 1).map { iri(it, "node") }.toList()
            check(result.size <= max) { "Node result exceeds limit $max" }
            result.toSet()
        }

    private fun readEdges(query: String, max: Int, timeout: Duration?): Set<Edge> =
        rows(query, timeout) { bindings ->
            val result = bindings.take(max + 1).map {
                Edge(iri(it, "subject"), iri(it, "predicate"), iri(it, "object"))
            }.toList()
            check(result.size <= max) { "Edge result exceeds limit $max" }
            result.toSet()
        }
}
