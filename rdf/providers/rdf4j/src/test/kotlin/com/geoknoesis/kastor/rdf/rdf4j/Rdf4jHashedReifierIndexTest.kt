package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.common.iteration.CloseableIteration
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.RepositoryResult
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reifiers of quoted triples too large for an encoded id have hashed ids (`kastor-star-sha256-...`), which do not
 * carry their triple. Resolving them must stay bounded: no scan of the store per operation, whatever the number of
 * such triples. Measured as the number of statements RDF4J hands back from scans (`getStatements` without a subject,
 * predicate or object).
 */
class Rdf4jHashedReifierIndexTest {
    private val ex = "http://example.org/"
    private val q = Iri(ex + "q")
    private val count = 200
    private val plain = 500

    /** Just large enough for a hashed id (see Rdf4jTerms.MAX_REIFIER_ID_LENGTH). */
    private val filler = "x".repeat(790_000)
    private fun term(i: Int) = TripleTerm(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("$i-$filler")))

    /** Counts the statements iterated by scans (unbound subject, predicate and object) on any connection. */
    private class CountingRepository(delegate: Repository) : RepositoryWrapper(delegate) {
        val scans = AtomicLong()
        val scanned = AtomicLong()
        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, delegate.connection) {
            override fun getStatements(subj: Resource?, pred: IRI?, obj: Value?, includeInferred: Boolean, vararg contexts: Resource?): RepositoryResult<Statement> {
                val inner = delegate.getStatements(subj, pred, obj, includeInferred, *contexts)
                if (subj != null || pred != null || obj != null) return inner
                scans.incrementAndGet()
                return RepositoryResult(object : CloseableIteration<Statement> {
                    override fun hasNext(): Boolean = inner.hasNext()
                    override fun next(): Statement = inner.next().also { scanned.incrementAndGet() }
                    override fun remove() = inner.remove()
                    override fun close() = inner.close()
                })
            }
        }
    }

    private fun counted(tracked: Boolean): Pair<Rdf4jRepository, CountingRepository> {
        val counting = CountingRepository(SailRepository(MemoryStore()).also { it.init() })
        val repo = Rdf4jRepository(counting).let { if (tracked) it.withVariant("memory") else it }
        repo.editDefaultGraph().addTriples((0 until plain).map { RdfTriple(Iri(ex + "plain$it"), Iri(ex + "p"), Literal("$it")) })
        return repo to counting
    }

    /** A repository with [count] annotated triples whose quoted triples are oversized, and their view. */
    private fun source(): Pair<Rdf4jRepository, List<RdfTriple>> {
        val repo = Rdf4jRepository.MemoryRepository()
        val vf = SimpleValueFactory.getInstance()
        repo.getRdf4jRepository().connection.use { conn ->
            conn.begin()
            for (i in 0 until count) {
                conn.add(Rdf4jTerms.toRdf4jValue(term(i)) as Resource, vf.createIRI(q.value), vf.createLiteral("z$i"))
            }
            conn.commit()
        }
        // Written behind the repository's back: tell it that triple values exist now.
        repo.update(UpdateQuery("INSERT DATA { << <${ex}a> <${ex}b> <${ex}c> >> <${ex}q> \"small\" }"))
        val view = repo.defaultGraph.getTriples().filter { (it.subject as BlankNode).id.contains("sha256-") }
        assertEquals(2 * count, view.size, "an annotation and its rdf:reifies triple per quoted triple")
        return repo to view
    }

    @Test
    fun `copying oversized annotated triples one by one into a factory store never scans`() {
        val (from, view) = source()
        val (repo, counting) = counted(tracked = true)
        from.use {
            repo.use {
                val graph = repo.editDefaultGraph()
                counting.scanned.set(0)
                view.forEach(graph::addTriple)
                assertEquals(0, counting.scanned.get(), "statements scanned while adding ${view.size} triples")
                assertEquals(view.toSet(), graph.getTriples().filter { it.subject is BlankNode }.toSet())
                assertEquals(plain + view.size, graph.size())

                // Lookups and removals through the hashed reifiers do not scan either.
                counting.scanned.set(0)
                counting.scans.set(0)
                for (triple in view) {
                    assertTrue(graph.hasTriple(triple))
                    assertEquals(2, graph.find(triple.subject, null, null).size)
                }
                assertEquals(0, counting.scanned.get(), "statements scanned by ${2 * view.size} lookups")
                for (triple in view.filter { it.predicate == q }) assertTrue(graph.removeTriple(triple), "remove $triple".take(200))
                assertEquals(0, counting.scanned.get(), "statements scanned by $count removals")
                assertEquals(count, graph.find(null, RDF.reifies, null).size, "the explicit rdf:reifies triples remain")
            }
        }
    }

    @Test
    fun `copying oversized annotated triples in one batch into a wrapped store scans at most once`() {
        val (from, view) = source()
        val (repo, counting) = counted(tracked = false)
        from.use {
            repo.use {
                val graph = repo.editDefaultGraph()
                counting.scanned.set(0)
                counting.scans.set(0)
                graph.addTriples(view)
                assertTrue(counting.scans.get() <= 1, "scans: ${counting.scans.get()}")
                assertTrue(counting.scanned.get() <= plain + 2L, "statements scanned: ${counting.scanned.get()}")
                assertEquals(view.toSet(), graph.getTriples().filter { it.subject is BlankNode }.toSet())
            }
        }
    }

    @Test
    fun `an unknown hashed reifier id is looked for once until the next update`() {
        val (repo, counting) = counted(tracked = true)
        repo.use {
            val graph = repo.editDefaultGraph()
            val unknown = BlankNode(Rdf4jTerms.STAR_REIFIER_PREFIX + "sha256-" + "0".repeat(64))
            val other = BlankNode(Rdf4jTerms.STAR_REIFIER_PREFIX + "sha256-" + "1".repeat(64))
            // No triple value was ever written: nothing to look for.
            counting.scans.set(0)
            repeat(20) { assertEquals(emptyList(), graph.find(unknown, null, null)) }
            assertEquals(0, counting.scans.get(), "no triple values in the store")

            // An update may have written any triple value: one scan answers for every unknown id until the next one.
            repo.update(UpdateQuery("INSERT DATA { << <${ex}a> <${ex}b> <${ex}c> >> <${ex}q> \"z\" }"))
            // (Let the repository re-derive where quoted subjects occur first: that is a scan of its own.)
            assertEquals(1, graph.find(null, RDF.reifies, null).size)
            counting.scans.set(0)
            repeat(20) { assertEquals(emptyList(), graph.find(unknown, null, null)) }
            repeat(20) { assertFalse(graph.hasTriple(RdfTriple(other, q, Literal("z")))) }
            assertEquals(1, counting.scans.get(), "one scan after the update")

            // Graph writes keep the index current: a new oversized triple resolves, an unknown id is still unknown.
            val big = term(0)
            val reifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(big) as org.eclipse.rdf4j.model.Triple)
            graph.addTriple(RdfTriple(reifier, RDF.reifies, big))
            graph.addTriple(RdfTriple(reifier, q, Literal("w")))
            assertEquals(2, graph.find(reifier, null, null).size)
            repeat(20) { assertEquals(emptyList(), graph.find(unknown, null, null)) }
            // An ordinary blank node with such an id is stored and found as written.
            graph.addTriple(RdfTriple(unknown, q, Literal("plain")))
            assertEquals(listOf(RdfTriple(unknown, q, Literal("plain"))), graph.find(unknown, null, null))
            assertTrue(graph.removeTriple(RdfTriple(unknown, q, Literal("plain"))))
            assertEquals(1, counting.scans.get(), "no scan after graph writes")

            assertEquals(2, graph.find(reifier, null, null).size, "a known id resolves without a scan")
            assertEquals(1, counting.scans.get())

            repo.update(UpdateQuery("INSERT DATA { << <${ex}a> <${ex}b> <${ex}d> >> <${ex}q> \"z\" }"))
            repeat(20) { assertEquals(emptyList(), graph.find(unknown, null, null)) }
            assertEquals(2, counting.scans.get(), "one more scan after another update")
        }
    }

    @Test
    fun `an update that writes an oversized quoted triple is found by its hashed reifier`() {
        val (repo, _) = counted(tracked = true)
        repo.use {
            val big = term(1)
            val reifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(big) as org.eclipse.rdf4j.model.Triple)
            val graph = repo.editDefaultGraph()
            assertEquals(emptyList(), graph.find(reifier, null, null))
            repo.update(UpdateQuery("INSERT DATA { << <${ex}s> <${ex}p> \"${(big.triple.obj as Literal).lexical}\" >> <${ex}q> \"z\" }"))
            val annotation = RdfTriple(reifier, q, Literal("z"))
            assertEquals(setOf(annotation, RdfTriple(reifier, RDF.reifies, big)), graph.find(reifier, null, null).toSet())
            assertTrue(graph.removeTriple(annotation))
            assertEquals(plain, graph.size())
        }
    }

    @Test
    fun `hashed reifiers of a store this process did not write resolve with one scan, whatever their number`() {
        val counting = CountingRepository(SailRepository(MemoryStore()).also { it.init() })
        val vf = SimpleValueFactory.getInstance()
        counting.connection.use { conn ->
            conn.begin()
            for (i in 0 until count) conn.add(Rdf4jTerms.toRdf4jValue(term(i)) as Resource, vf.createIRI(q.value), vf.createLiteral("z$i"))
            conn.commit()
        }
        Rdf4jRepository(counting).use { repo ->
            val graph = repo.editDefaultGraph()
            val annotations = graph.find(null, q, null)
            assertEquals(count, annotations.size)
            // As after a restart: the ids are known to the caller, not to the repository.
            repo.forgetHashedReifiers()
            counting.scans.set(0)
            for (annotation in annotations) {
                assertTrue(graph.hasTriple(annotation))
                assertTrue(graph.removeTriple(annotation), "every removal finds its statement")
            }
            assertEquals(1, counting.scans.get(), "one scan resolves every hashed reifier of the store")
            assertEquals(0, graph.size())
        }
    }

    @Test
    fun `a hashed reifier is resolved per repository`() {
        val big = term(2)
        val reifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(big) as org.eclipse.rdf4j.model.Triple)
        val annotation = RdfTriple(reifier, q, Literal("z"))
        Rdf4jRepository.MemoryRepository().use { first ->
            Rdf4jRepository.MemoryRepository().use { second ->
                first.editDefaultGraph().addTriples(listOf(RdfTriple(reifier, RDF.reifies, big), annotation))
                assertEquals(2, first.defaultGraph.find(reifier, null, null).size)
                // The other repository holds triple values, but not this one: there the id is an ordinary blank node.
                second.editDefaultGraph().addTriple(RdfTriple(Iri(ex + "s"), Iri(ex + "says"), term(3)))
                assertEquals(emptyList(), second.defaultGraph.find(reifier, null, null))
                second.editDefaultGraph().addTriple(annotation)
                assertEquals(listOf(annotation), second.defaultGraph.find(reifier, null, null), "no rdf:reifies triple is implied")
                second.getRdf4jRepository().connection.use { conn ->
                    conn.getStatements(null, org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI(q.value), null, false).use { result ->
                        assertTrue(result.next().subject is org.eclipse.rdf4j.model.BNode, "stored on the plain blank node")
                    }
                }
                // Adding its rdf:reifies triple makes it the reifier of that triple in the second repository too.
                second.editDefaultGraph().addTriple(RdfTriple(reifier, RDF.reifies, big))
                assertEquals(setOf(annotation, RdfTriple(reifier, RDF.reifies, big)), second.defaultGraph.find(reifier, null, null).toSet())
                assertEquals(first.defaultGraph.getTriples().toSet(), second.defaultGraph.getTriples().filter { it.subject == reifier }.toSet())
            }
        }
    }
}
