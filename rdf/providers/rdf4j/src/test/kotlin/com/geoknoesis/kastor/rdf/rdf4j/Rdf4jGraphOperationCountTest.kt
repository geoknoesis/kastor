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
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.RepositoryResult
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `size()` and reifier lookups must not scan the store: they are measured as the number of statements RDF4J hands
 * back through `getStatements`, on graphs of 100,000 statements.
 */
class Rdf4jGraphOperationCountTest {
    @TempDir
    lateinit var tmp: Path

    private val size = 100_000
    private val p = Iri("http://example.org/p")

    /** Counts every statement iterated from `getStatements` on any connection of the wrapped repository. */
    private class CountingRepository(delegate: Repository) : RepositoryWrapper(delegate) {
        val iterated = AtomicLong()
        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, delegate.connection) {
            override fun getStatements(subj: Resource?, pred: IRI?, obj: Value?, includeInferred: Boolean, vararg contexts: Resource?): RepositoryResult<Statement> {
                val inner = delegate.getStatements(subj, pred, obj, includeInferred, *contexts)
                return RepositoryResult(object : CloseableIteration<Statement> {
                    override fun hasNext(): Boolean = inner.hasNext()
                    override fun next(): Statement = inner.next().also { iterated.incrementAndGet() }
                    override fun remove() = inner.remove()
                    override fun close() = inner.close()
                })
            }
        }
    }

    private fun counted(sail: org.eclipse.rdf4j.sail.Sail, variant: String): Pair<Rdf4jRepository, CountingRepository> {
        val base = SailRepository(sail).also { it.init() }
        val counting = CountingRepository(base)
        return Rdf4jRepository(counting).withVariant(variant) to counting
    }

    private fun load(repo: Rdf4jRepository) =
        repo.editDefaultGraph().addTriples((0 until size).asSequence().map { RdfTriple(Iri("http://example.org/s$it"), p, Literal("$it")) })

    @Test
    fun `size of a plain memory store uses the store count`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            counting.iterated.set(0)
            assertEquals(size, it.defaultGraph.size())
            assertEquals(0, counting.iterated.get(), "size() must not iterate statements")
        }
    }

    @Test
    fun `size of a native store uses the store count`() {
        val (repo, counting) = counted(NativeStore(tmp.resolve("native").toFile()), "native")
        repo.use {
            load(it)
            counting.iterated.set(0)
            assertEquals(size, it.defaultGraph.size())
            assertEquals(0, counting.iterated.get(), "size() must not iterate statements")
        }
    }

    @Test
    fun `reifier lookups on a large store with RDF-star subjects are index lookups`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            Rdf4jProvider().parseDataset(it, "<< <http://example.org/a> <http://example.org/b> <http://example.org/c> >> <http://example.org/q> \"z\" .".byteInputStream(), "TURTLE")
            val graph = it.editDefaultGraph()
            val annotation = graph.find(null, Iri("http://example.org/q"), null).single()
            val reifier = annotation.subject as BlankNode
            val quoted = TripleTerm(RdfTriple(Iri("http://example.org/a"), Iri("http://example.org/b"), Iri("http://example.org/c")))

            counting.iterated.set(0)
            assertEquals(size + 2, graph.size(), "annotation plus its rdf:reifies triple")
            assertTrue(counting.iterated.get() <= size + 1L, "one counting pass at most, iterated ${counting.iterated.get()}")

            counting.iterated.set(0)
            assertEquals(setOf(annotation, RdfTriple(reifier, RDF.reifies, quoted)), graph.find(reifier, null, null).toSet())
            assertTrue(graph.hasTriple(RdfTriple(reifier, RDF.reifies, quoted)))
            assertEquals(listOf(RdfTriple(reifier, RDF.reifies, quoted)), graph.find(null, RDF.reifies, quoted))
            assertTrue(graph.hasTriple(annotation))
            assertTrue(counting.iterated.get() < 20, "reifier lookups must be targeted, iterated ${counting.iterated.get()}")

            counting.iterated.set(0)
            assertTrue(graph.removeTriple(annotation))
            assertTrue(counting.iterated.get() < 20, "reifier removal must be targeted, iterated ${counting.iterated.get()}")
            assertEquals(size, graph.size())
        }
    }

    @Test
    fun `size of a lenient factory store without RDF-star subjects uses the store count`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.lenient().use {
            load(it)
            Rdf4jProvider().parseDataset(it, "<http://example.org/x> <http://example.org/p> \"parsed\"@en .".byteInputStream(), "TURTLE")
            counting.iterated.set(0)
            assertEquals(size + 1, it.defaultGraph.size())
            assertEquals(0, counting.iterated.get(), "size() must not iterate statements")
            // A SPARQL update may write statements Kastor cannot represent: from then on size() counts convertible ones.
            it.update(UpdateQuery("INSERT DATA { <http://example.org/y> <http://example.org/p> \"u\" }"))
            assertEquals(size + 2, it.defaultGraph.size())
        }
    }

    @Test
    fun `a SPARQL update that cannot create quoted subjects does not force a rescan`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            it.update(UpdateQuery("INSERT DATA { <http://example.org/x> <http://example.org/p> \"extra\" }"))
            it.update(UpdateQuery("DELETE { ?s <http://example.org/p> \"0\" } INSERT { ?s <http://example.org/p2> \"moved\" } WHERE { ?s <http://example.org/p> \"0\" }"))
            counting.iterated.set(0)
            assertEquals(size + 1, it.defaultGraph.size())
            assertEquals(0, counting.iterated.get(), "size() after a plain update must not iterate statements")
        }
    }

    @Test
    fun `an update that moves stored triple terms into subject position is still seen`() {
        val (repo, _) = counted(MemoryStore(), "memory")
        repo.use {
            val quoted = TripleTerm(RdfTriple(Iri("http://example.org/a"), Iri("http://example.org/b"), Iri("http://example.org/c")))
            it.editDefaultGraph().addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/r"), quoted))
            assertEquals(1, it.defaultGraph.size())
            // No RDF-star syntax in the update, but it turns the stored triple term into a quoted subject.
            it.update(UpdateQuery("INSERT { ?o <http://example.org/q> \"z\" } WHERE { ?s <http://example.org/r> ?o }"))
            assertEquals(3, it.defaultGraph.size(), "the new statement plus its rdf:reifies triple")
            assertEquals(1, it.defaultGraph.find(null, RDF.reifies, null).size)
        }
    }

    @Test
    fun `inside a transaction the quoted-subject scan after an update runs once`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            val quoted = TripleTerm(RdfTriple(Iri("http://example.org/a"), Iri("http://example.org/b"), Iri("http://example.org/c")))
            val reifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(quoted) as org.eclipse.rdf4j.model.Triple)
            it.transaction {
                update(UpdateQuery("INSERT DATA { << <http://example.org/a> <http://example.org/b> <http://example.org/c> >> <http://example.org/q> \"z\" }"))
                val graph = editDefaultGraph()
                counting.iterated.set(0)
                repeat(5) { assertEquals(2, graph.find(reifier, null, null).size) }
                assertTrue(counting.iterated.get() <= size + 50L, "one scan at most inside the transaction, iterated ${counting.iterated.get()}")
                // A graph write in the same transaction keeps the cached result valid.
                graph.addTriple(RdfTriple(reifier, Iri("http://example.org/q2"), Literal("w")))
                counting.iterated.set(0)
                repeat(5) { assertEquals(3, graph.find(reifier, null, null).size) }
                assertTrue(counting.iterated.get() < 50L, "no rescan after a graph write, iterated ${counting.iterated.get()}")
            }
        }
    }

    @Test
    fun `a SPARQL update that creates quoted subjects is seen by size`() {
        val (repo, _) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            it.update(UpdateQuery("INSERT DATA { << <http://example.org/a> <http://example.org/b> <http://example.org/c> >> <http://example.org/q> \"z\" }"))
            assertEquals(size + 2, it.defaultGraph.size())
            it.update(UpdateQuery("DELETE WHERE { ?s <http://example.org/q> ?o }"))
            assertEquals(size, it.defaultGraph.size())
        }
    }

    private val ex = "http://example.org/"
    private val quoted = TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "b"), Iri(ex + "c")))
    private val reifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(quoted) as org.eclipse.rdf4j.model.Triple)
    private val reifies = RdfTriple(reifier, RDF.reifies, quoted)
    private val annotation = RdfTriple(reifier, Iri(ex + "q"), Literal("z"))

    /** Lookups through a known reifier, which the top-level statements about its quoted triple answer. */
    private fun assertTargetedReifierLookups(repo: Rdf4jRepository, counting: CountingRepository, what: String) {
        val graph = repo.editDefaultGraph()
        counting.iterated.set(0)
        assertEquals(setOf(annotation, reifies), graph.find(reifier, null, null).toSet(), what)
        assertEquals(listOf(annotation), graph.find(reifier, Iri(ex + "q"), null), what)
        assertTrue(graph.hasTriple(annotation), what)
        assertTrue(graph.hasTriple(reifies), what)
        assertEquals(listOf(reifies), graph.find(reifier, RDF.reifies, null), what)
        assertEquals(listOf(reifies), graph.find(null, RDF.reifies, quoted), what)
        assertEquals(emptyList(), graph.find(reifier, Iri(ex + "none"), null), what)
        assertTrue(!graph.hasTriple(RdfTriple(reifier, Iri(ex + "q"), Literal("other"))), what)
        assertTrue(counting.iterated.get() < 40, "$what: reifier lookups must be targeted, iterated ${counting.iterated.get()}")
        counting.iterated.set(0)
        assertTrue(graph.removeTriple(annotation), what)
        assertTrue(counting.iterated.get() < 20, "$what: removing an annotation must be targeted, iterated ${counting.iterated.get()}")
        assertTrue(!graph.hasTriple(annotation), what)
    }

    @Test
    fun `reifier lookups on a wrapped store whose quoted subjects are unknown are index lookups`() {
        val base = SailRepository(MemoryStore()).also { it.init() }
        val counting = CountingRepository(base)
        Rdf4jRepository(counting).use { repo ->
            load(repo)
            repo.editDefaultGraph().addTriple(annotation)
            assertTargetedReifierLookups(repo, counting, "wrapped store")
        }
    }

    @Test
    fun `reifier lookups on a store with nested quoted subjects are index lookups`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            val nested = "<< << <${ex}n1> <${ex}n2> <${ex}n3> >> <${ex}m> \"1\" >> <${ex}r> \"w\" ."
            Rdf4jProvider().parseDataset(it, nested.byteInputStream(), "TURTLE")
            val graph = it.editDefaultGraph()
            graph.addTriple(annotation)
            assertTargetedReifierLookups(it, counting, "nested store")

            // A quoted triple that only occurs nested is still found (by the scan that the index lookups fall back to).
            val inner = TripleTerm(RdfTriple(Iri(ex + "n1"), Iri(ex + "n2"), Iri(ex + "n3")))
            val innerReifier = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(inner) as org.eclipse.rdf4j.model.Triple)
            val innerReifies = RdfTriple(innerReifier, RDF.reifies, inner)
            assertTrue(graph.hasTriple(innerReifies))
            assertEquals(listOf(innerReifies), graph.find(innerReifier, RDF.reifies, null))
            assertEquals(listOf(innerReifies), graph.find(null, RDF.reifies, inner))
            assertEquals(setOf(innerReifies), graph.find(innerReifier, null, null).toSet())
            assertEquals(2, graph.find(null, RDF.reifies, null).size)
            // Removing that rdf:reifies triple rewrites the nested statement, like before.
            assertTrue(graph.removeTriple(innerReifies))
            assertTrue(!graph.hasTriple(innerReifies))
        }
    }

    @Test
    fun `an rdf-reifies lookup with a non-triple object does not scan`() {
        val (repo, counting) = counted(MemoryStore(), "memory")
        repo.use {
            load(it)
            it.editDefaultGraph().addTriple(annotation)
            counting.iterated.set(0)
            assertEquals(emptyList(), it.defaultGraph.find(null, RDF.reifies, Iri(ex + "a")))
            assertTrue(counting.iterated.get() < 20, "iterated ${counting.iterated.get()}")
        }
    }
}
