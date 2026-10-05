package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Whether a graph is compared with `equals` (handle equality) rather than by instance. This is an explicit
 * contract (see the class documentation), never inferred from the shape of a foreign class: a data class, a Java
 * record or a collection is not handle-equal however its `equals` is declared.
 */
@KastorGenInternalApi
internal object HandleEquality : ClassValue<Boolean>() {
    private const val KASTOR_RDF_PACKAGE = "com.geoknoesis.kastor.rdf."

    fun of(graph: RdfGraph): Boolean = get(graph.javaClass)

    override fun computeValue(type: Class<*>): Boolean {
        val declared = VersionedRdfGraph::class.java.isAssignableFrom(type) ||
            HandleEqualGraph::class.java.isAssignableFrom(type) ||
            type.name.startsWith(KASTOR_RDF_PACKAGE)
        if (!declared) return false
        // Without an override the graph is identified by instance, which needs no equals call.
        return try {
            type.getMethod("equals", Any::class.java).declaringClass != Any::class.java &&
                type.getMethod("hashCode").declaringClass != Any::class.java
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/** Content digest of a graph, see [GraphDigester]. Digests of different digesters are not comparable. */
internal data class GraphDigest(val w0: Long, val w1: Long, val w2: Long, val w3: Long, val count: Long)

/**
 * Computes the content digest of a graph: the sum modulo 2^256 of `SHA-256(salt || encoding(triple))` over the
 * triples, plus the triple count. The encoding of a triple is unambiguous (term kind tags, length-prefixed
 * values), and the sum makes the digest independent of the order in which a store returns its triples, in one
 * pass and without sorting.
 *
 * ## What the digest guarantees
 * Two different contents produced without knowledge of [salt] have the same digest with negligible probability
 * (unlike a sum of `hashCode()`s, where e.g. the literals `"Aa"` and `"BB"` collide).
 *
 * A sum of hashes is, on its own, **not** collision resistant against crafted data: with unsalted hashes anyone
 * can compute the per-triple values offline and solve for a second set of triples with the same sum (the
 * generalised birthday attack on additive hashes, far below 2^128 work for a 256-bit sum). The salt closes that
 * off: it is 32 random bytes drawn per digester (per cache instance, never exposed, never persisted), so the
 * per-triple values of this process cannot be computed by a party that only controls the data, and a collision
 * cannot be prepared in advance. The digest is therefore suitable for what it is used for (deciding whether a
 * cached state was built from this content) and for nothing else: it is not a stable or public fingerprint, and
 * it does not resist a party that can read the salt (i.e. the memory of this process).
 *
 * One digester computes digests without allocating per triple: values are fed to the hash through one reused
 * buffer, as UTF-16 code units.
 */
internal class GraphDigester(salt: ByteArray = randomSalt()) {
    private val salt: ByteArray = salt.copyOf()

    fun digest(triples: Iterable<RdfTriple>): GraphDigest = sum(triples) { sink, triple ->
        sink.term(triple.subject)
        sink.term(triple.predicate)
        sink.term(triple.obj)
    }

    /** The digest of a content given as one unambiguous string per triple (see [NativeLoader.encode]). */
    fun digestEncoded(triples: Iterable<String>): GraphDigest = sum(triples) { sink, encoded -> sink.encoded(encoded) }

    private inline fun <T> sum(items: Iterable<T>, feed: (Sink, T) -> Unit): GraphDigest {
        val sha = MessageDigest.getInstance("SHA-256")
        val sink = Sink(sha)
        val out = ByteArray(32)
        var s0 = 0L
        var s1 = 0L
        var s2 = 0L
        var s3 = 0L
        var count = 0L
        for (item in items) {
            sha.update(salt)
            feed(sink, item)
            sink.flush()
            sha.digest(out, 0, 32)
            // (s0..s3) += out, as big-endian 256-bit integers, modulo 2^256.
            val a3 = long(out, 24)
            val r3 = s3 + a3
            var carry = if (java.lang.Long.compareUnsigned(r3, a3) < 0) 1L else 0L
            val t2 = s2 + long(out, 16)
            val r2 = t2 + carry
            carry = if (java.lang.Long.compareUnsigned(t2, s2) < 0 || java.lang.Long.compareUnsigned(r2, t2) < 0) 1L else 0L
            val t1 = s1 + long(out, 8)
            val r1 = t1 + carry
            carry = if (java.lang.Long.compareUnsigned(t1, s1) < 0 || java.lang.Long.compareUnsigned(r1, t1) < 0) 1L else 0L
            s0 += long(out, 0) + carry
            s1 = r1
            s2 = r2
            s3 = r3
            count++
        }
        return GraphDigest(s0, s1, s2, s3, count)
    }

    private fun long(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in at until at + 8) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }

    /** Feeds the encoding of terms to [sha] through one reused buffer. */
    private class Sink(private val sha: MessageDigest) {
        private val buffer = ByteArray(1024)
        private var position = 0

        fun flush() {
            if (position > 0) {
                sha.update(buffer, 0, position)
                position = 0
            }
        }

        private fun byte(value: Int) {
            if (position == buffer.size) flush()
            buffer[position++] = value.toByte()
        }

        private fun field(value: String?) {
            if (value == null) {
                byte(0)
                return
            }
            byte(1)
            val length = value.length
            byte(length ushr 24)
            byte(length ushr 16)
            byte(length ushr 8)
            byte(length)
            for (i in 0 until length) {
                val c = value[i].code
                byte(c ushr 8)
                byte(c)
            }
        }

        /** A triple in a provider's own encoding; the tag keeps it apart from the encoding of Kastor terms. */
        fun encoded(value: String) {
            byte('N'.code)
            field(value)
        }

        fun term(term: RdfTerm) {
            when (term) {
                is Iri -> { byte('I'.code); field(term.value) }
                is BlankNode -> { byte('B'.code); field(term.id) }
                is LangString -> {
                    byte('L'.code); field(term.lexical); field(term.lang); field(term.direction?.toString())
                }
                is Literal -> { byte('T'.code); field(term.lexical); field(term.datatype.value) }
                is TripleTerm -> {
                    byte('R'.code)
                    term(term.triple.subject)
                    term(term.triple.predicate)
                    term(term.triple.obj)
                }
                else -> { byte('?'.code); field(term.toString()) }
            }
        }
    }

    private companion object {
        private val random = SecureRandom()
        fun randomSalt(): ByteArray = ByteArray(32).also(random::nextBytes)
    }
}
