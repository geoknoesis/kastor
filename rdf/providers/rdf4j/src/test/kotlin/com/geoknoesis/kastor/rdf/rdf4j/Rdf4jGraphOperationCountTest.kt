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
}
