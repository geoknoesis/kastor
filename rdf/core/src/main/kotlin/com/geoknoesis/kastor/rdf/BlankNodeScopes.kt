package com.geoknoesis.kastor.rdf

import java.util.Base64
import java.util.IdentityHashMap

/**
 * Blank-node namespaces for merging independently sourced graphs without conflating their local labels.
 *
 * Reuse one instance for an entire merge. Repeated references to a graph, and source-tracked graphs from one
 * repository, share a namespace. All other graphs have separate namespaces. IRIs and literals stay unchanged;
 * blank nodes in nested triple terms are mapped consistently with ordinary subjects and objects.
 *
 * [graph] returns a live, read-only view. Labels are deterministic for the order sources are first encountered;
 * obtain mapped blank nodes from the view before using its indexed lookups. Instances are not thread-safe.
 *
 * When [preservedGraph] is supplied, its blank nodes keep their original identities, including references to
 * those nodes in other graphs from the same source. They are read at construction and reserved, including those
 * nested in triple terms, so scoped nodes cannot collide with them. Keep this graph unchanged during the merge.
 * This supports shapes that refer to data blank nodes.
 */
class BlankNodeScopes(preservedGraph: RdfGraph? = null) {
    private val sources = IdentityHashMap<Any, Int>()
    private val preservedSource = preservedGraph?.let(::source)
    private val preservedGraph = preservedGraph?.asGraphRef()?.getReferencedGraph()
    private val reservedIds = HashSet<String>()

    init {
        fun reserve(term: RdfTerm) {
            when (term) {
                is BlankNode -> reservedIds.add(term.id)
                is TripleTerm -> { reserve(term.triple.subject); reserve(term.triple.obj) }
                else -> Unit
            }
        }
        preservedGraph?.getTriplesSequence()?.forEach { reserve(it.subject); reserve(it.obj) }
    }

    private fun source(graph: RdfGraph): Any = graph.asGraphRef().let {
        it.sourceRepository ?: it.getReferencedGraph()
    }

    fun graph(graph: RdfGraph): RdfGraph {
        val ref = graph.asGraphRef()
        val source = source(ref)
        if (ref.getReferencedGraph() === preservedGraph) return ref.getReferencedGraph()
        val scope = sources[source] ?: sources.size.also { sources[source] = it }
        return ScopedGraph(
            ref.getReferencedGraph(), "kastor_scope_${scope}_", reservedIds,
            if (source === preservedSource) reservedIds else emptySet(),
        )
    }

    /** Maps a term with the same namespace as [graph], for references held outside its triples (e.g. metadata). */
    fun mapTerm(graph: RdfGraph, term: RdfTerm): RdfTerm =
        (this.graph(graph) as? ScopedGraph)?.mapTerm(term) ?: term

    private class ScopedGraph(
        private val graph: RdfGraph,
        private val prefix: String,
        private val reservedIds: Set<String>,
        private val preservedIds: Set<String>,
    ) : RdfGraph {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun mapTerm(value: RdfTerm): RdfTerm = term(value, inverse = false)!!

        // BlankNode ids can contain arbitrary UTF-16 code units, including unpaired surrogates.
        // Charset encoders replace those units, so encode them directly to keep the mapping injective.
        private fun encode(id: String): String = encoder.encodeToString(ByteArray(id.length * 2).also { bytes ->
            id.forEachIndexed { i, c ->
                bytes[2 * i] = (c.code ushr 8).toByte()
                bytes[2 * i + 1] = c.code.toByte()
            }
        })

        private fun decode(id: String): String {
            val bytes = decoder.decode(id)
            require(bytes.size % 2 == 0) { "Invalid scoped blank node id" }
            return CharArray(bytes.size / 2) { i ->
                (((bytes[2 * i].toInt() and 255) shl 8) or (bytes[2 * i + 1].toInt() and 255)).toChar()
            }.concatToString()
        }

        private fun term(value: RdfTerm, inverse: Boolean): RdfTerm? = when (value) {
            is BlankNode -> if (value.id in preservedIds) value else if (!inverse) {
                var id = prefix + encode(value.id)
                while (id in reservedIds) id = "_$id"
                BlankNode(id)
            } else {
                val id = value.id.trimStart('_')
                if (!id.startsWith(prefix)) null else try {
                    val original = BlankNode(decode(id.removePrefix(prefix)))
                    original.takeIf { term(it, inverse = false) == value }
                } catch (_: IllegalArgumentException) { null }
            }
            is TripleTerm -> triple(value.triple, inverse)?.let(::TripleTerm)
            else -> value
        }

        private fun triple(value: RdfTriple, inverse: Boolean): RdfTriple? {
            val subject = term(value.subject, inverse) as? RdfResource ?: return null
            val obj = term(value.obj, inverse) ?: return null
            return RdfTriple(subject, value.predicate, obj)
        }

        override fun getTriples(): List<RdfTriple> = getTriplesSequence().toList()
        override fun getTriplesSequence(): Sequence<RdfTriple> = graph.getTriplesSequence().map { triple(it, false)!! }
        override fun size(): Int = graph.size()
        override fun hasTriple(triple: RdfTriple): Boolean = triple(triple, true)?.let(graph::hasTriple) ?: false

        override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
            val originalSubject = subject?.let { term(it, true) as? RdfResource ?: return emptyList() }
            val originalObject = obj?.let { term(it, true) ?: return emptyList() }
            return graph.find(originalSubject, predicate, originalObject).map { triple(it, false)!! }
        }
    }
}
