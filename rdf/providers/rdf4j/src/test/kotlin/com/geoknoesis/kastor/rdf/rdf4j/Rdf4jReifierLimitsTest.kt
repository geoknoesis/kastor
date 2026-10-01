package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
    }

    private fun huge(fill: Char) =
        vf.createTriple(vf.createIRI("http://example.org/s"), p, vf.createLiteral(fill.toString().repeat(Rdf4jTerms.MAX_REIFIER_ID_LENGTH)))

    @Test
    fun `a quoted triple too large for an encoded id gets a bounded hashed reifier`() {
        val reifier = Rdf4jTerms.reifierFor(huge('x'))
        assertTrue(reifier.id.length < 128, "bounded id, got ${reifier.id.length} characters")
        assertEquals(reifier, Rdf4jTerms.reifierFor(huge('x')), "deterministic")
        assertNotEquals(reifier, Rdf4jTerms.reifierFor(huge('y')))
        assertEquals(huge('x'), Rdf4jTerms.quotedTripleOf(reifier.id))
        assertTrue(Rdf4jTerms.mentionsStarReifier(reifier))
        // A hashed id nobody produced is an ordinary blank node.
        assertNull(Rdf4jTerms.quotedTripleOf(Rdf4jTerms.STAR_REIFIER_PREFIX + "sha256-" + "0".repeat(64)))
    }

    private val megabyte = "x".repeat(1 shl 20)
    private val q = Iri("http://example.org/q")
    private val bigDocument = "<< <http://example.org/s> <http://example.org/p> \"$megabyte\" >> <http://example.org/q> \"z\" ."
    private val bigTerm = TripleTerm(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), Literal(megabyte)))

    private fun assertBigAnnotationWorks(repo: Rdf4jRepository) {
        val graph = repo.editDefaultGraph()
        val triples = graph.getTriples()
        assertEquals(2, triples.size, "the annotation and its rdf:reifies triple")
        val annotation = triples.single { it.predicate == q }
        val reifier = annotation.subject as BlankNode
        assertTrue(reifier.id.length < 128, "bounded id, got ${reifier.id.length} characters")
        val reifies = RdfTriple(reifier, RDF.reifies, bigTerm)
        assertEquals(setOf(annotation, reifies), triples.toSet())
        assertEquals(2, graph.size())
        // Lookups by the hashed reifier resolve it, also when this process has not seen its triple (e.g. a restart).
        for (forget in listOf(false, true)) {
            if (forget) Rdf4jTerms.forgetHashedReifiers()
            assertEquals(setOf(annotation, reifies), graph.find(reifier, null, null).toSet(), "forget=$forget")
            if (forget) Rdf4jTerms.forgetHashedReifiers()
            assertTrue(graph.hasTriple(annotation), "forget=$forget")
            if (forget) Rdf4jTerms.forgetHashedReifiers()
            assertTrue(graph.hasTriple(reifies), "forget=$forget")
            assertEquals(listOf(reifies), graph.find(null, RDF.reifies, bigTerm), "forget=$forget")
        }
        // Writing through the hashed reifier stores the RDF-star form again.
        Rdf4jTerms.forgetHashedReifiers()
        val second = RdfTriple(reifier, Iri("http://example.org/q2"), Literal("w"))
        graph.addTriple(second)
        assertTrue(repo.ask(SparqlAskQuery("ASK { << <http://example.org/s> <http://example.org/p> ?big >> <http://example.org/q2> \"w\" }")))
        assertEquals(setOf(annotation, second, reifies), graph.getTriples().toSet())
        Rdf4jTerms.forgetHashedReifiers()
        assertTrue(graph.removeTriple(annotation))
        assertTrue(graph.removeTriple(second))
        assertEquals(0, graph.size())
    }

    @Test
    fun `an annotated triple with a 1 MB literal can be read, looked up and removed`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            Rdf4jProvider().parseDataset(repo, bigDocument.byteInputStream(), "TURTLE")
            assertBigAnnotationWorks(repo)
        }
    }

    @Test
    fun `an annotated triple with a 1 MB literal in a wrapped store can be read, looked up and removed`() {
        val store = SailRepository(MemoryStore()).also { it.init() }
        store.connection.use { conn ->
            val big = vf.createTriple(vf.createIRI("http://example.org/s"), p, vf.createLiteral(megabyte))
            conn.add(big, vf.createIRI("http://example.org/q"), vf.createLiteral("z"))
        }
        Rdf4jRepository(store).use { repo -> assertBigAnnotationWorks(repo) }
    }

    @Test
    fun `parsing a graph with a 1 MB literal inside an annotated triple does not fail`() {
        val triples = Rdf4jProvider().parseGraph(bigDocument.byteInputStream(), "TURTLE").getTriples()
        assertEquals(2, triples.size)
        assertTrue(triples.all { (it.subject as BlankNode).id.length < 128 })
    }

    @Test
    fun `an unknown hashed reifier id is an ordinary blank node`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val node = BlankNode(Rdf4jTerms.STAR_REIFIER_PREFIX + "sha256-" + "0".repeat(64))
            val triple = RdfTriple(node, Iri("http://example.org/p"), Literal("v"))
            val graph = repo.editDefaultGraph()
            graph.addTriple(triple)
            assertEquals(listOf(triple), graph.find(node, null, null))
            assertEquals(1, graph.size())
            assertTrue(graph.removeTriple(triple))
        }
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
