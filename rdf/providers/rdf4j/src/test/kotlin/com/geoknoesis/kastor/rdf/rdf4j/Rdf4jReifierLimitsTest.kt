package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Reifier ids are decoded with bounded nesting and length, so a crafted blank-node id cannot overflow the stack. */
class Rdf4jReifierLimitsTest {
    private val vf = SimpleValueFactory.getInstance()
    private val p = vf.createIRI("http://example.org/p")
    private val o = vf.createIRI("http://example.org/o")

    /** Forges a reifier id (valid checksum) whose encoded triple nests [depth] quoted subjects. */
    private fun forgedId(depth: Int): String {
        val text = StringBuilder()
        repeat(depth) { text.append('T') }
        text.append("I20:http://example.org/s")
        repeat(depth) { text.append("I20:http://example.org/pI20:http://example.org/o") }
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = (0 until 4).joinToString("") { "%02x".format(digest[it]) }
        return Rdf4jTerms.STAR_REIFIER_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "-" + hex
    }

    private fun nested(depth: Int): Resource {
        var subject: Resource = vf.createIRI("http://example.org/s")
        repeat(depth) { subject = vf.createTriple(subject, p, o) }
        return subject
    }

    @Test
    fun `a forged deeply nested reifier id fails with a typed exception instead of overflowing the stack`() {
        val id = forgedId(10_000)
        assertFailsWith<ReifierLimitException> { Rdf4jTerms.quotedTripleOf(id) }
        // The same guard applies to graph operations that inspect the blank node.
        Rdf4jRepository.MemoryRepository().use { repo ->
            assertFailsWith<ReifierLimitException> { repo.defaultGraph.find(BlankNode(id), null, null) }
        }
    }

    @Test
    fun `nesting up to the limit round-trips and one level more is rejected`() {
        val atLimit = nested(Rdf4jTerms.MAX_REIFIER_NESTING) as org.eclipse.rdf4j.model.Triple
        val reifier = Rdf4jTerms.reifierFor(atLimit)
        assertEquals(atLimit, Rdf4jTerms.quotedTripleOf(reifier.id))
        assertFailsWith<ReifierLimitException> { Rdf4jTerms.quotedTripleOf(forgedId(Rdf4jTerms.MAX_REIFIER_NESTING + 1)) }
        assertFailsWith<ReifierLimitException> {
            Rdf4jTerms.reifierFor(nested(Rdf4jTerms.MAX_REIFIER_NESTING + 1) as org.eclipse.rdf4j.model.Triple)
        }
    }

    @Test
    fun `over-long reifier ids are rejected before decoding`() {
        val id = Rdf4jTerms.STAR_REIFIER_PREFIX + "A".repeat(Rdf4jTerms.MAX_REIFIER_ID_LENGTH) + "-deadbeef"
        val error = assertFailsWith<ReifierLimitException> { Rdf4jTerms.quotedTripleOf(id) }
        assertTrue(error.message!!.contains("length"), error.message)
        val huge = vf.createTriple(vf.createIRI("http://example.org/s"), p, vf.createLiteral("x".repeat(Rdf4jTerms.MAX_REIFIER_ID_LENGTH)))
        assertFailsWith<ReifierLimitException> { Rdf4jTerms.reifierFor(huge) }
    }

    @Test
    fun `ordinary blank nodes with the reifier prefix are unaffected`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val triple = RdfTriple(BlankNode("kastor-star-foo"), Iri("http://example.org/p"), Literal("v"))
            repo.editDefaultGraph().addTriple(triple)
            assertEquals(listOf(triple), repo.defaultGraph.find(BlankNode("kastor-star-foo"), null, null))
        }
    }
}
