package com.geoknoesis.kastor.rdf

import java.util.Base64
import java.util.IdentityHashMap
import java.util.UUID

/** One blank-node namespace per independent source, shared by every view and query of a dataset. */
internal class DatasetGraphScopes(refs: List<GraphRef>) {
    private val sources = IdentityHashMap<Any, Int>()
    private val prefix = "kastor_${UUID.randomUUID().toString().replace("-", "")}_"

    init {
        refs.forEach { ref ->
            val source = ref.sourceRepository ?: ref.getReferencedGraph()
            if (!sources.containsKey(source)) sources[source] = sources.size
        }
    }

    fun graph(ref: GraphRef): RdfGraph {
        if (sources.size <= 1) return ref.getReferencedGraph()
        val scope = sources.getValue(ref.sourceRepository ?: ref.getReferencedGraph())
        return ScopedGraph(ref.getReferencedGraph(), "$prefix${scope}_")
    }

    private class ScopedGraph(private val graph: RdfGraph, private val prefix: String) : RdfGraph {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

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
            is BlankNode -> if (!inverse) {
                BlankNode(prefix + encode(value.id))
            } else {
                if (!value.id.startsWith(prefix)) null else try {
                    val original = BlankNode(decode(value.id.removePrefix(prefix)))
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
