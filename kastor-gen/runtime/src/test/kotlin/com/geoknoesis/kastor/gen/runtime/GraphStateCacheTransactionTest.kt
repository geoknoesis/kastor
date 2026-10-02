@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The cache against a repository whose modification stamps follow the transaction contract: the stamp identifies the
 * content the **calling thread** would read now. A thread with uncommitted writes gets stamps no other thread ever
 * sees; every other thread keeps the committed stamp until the commit.
 */
class GraphStateCacheTransactionTest {

    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun triple(i: Int) = RdfTriple(ex("s$i"), ex("p"), Literal("v$i"))

    private class State(val triples: Set<RdfTriple>) {
        @Volatile var released = false
    }

    private class Fixture(exclusive: Boolean, max: Int = 4) {
        val released: MutableList<State> = Collections.synchronizedList(ArrayList())
        val cache = GraphStateCache<State>(
            maxEntries = max,
            exclusive = exclusive,
            load = { triples, _ -> State(triples.toSet()) },
            release = { state ->
                state.released = true
                released += state
            },
            owner = "TestCache",
        )
        fun contentOf(graph: RdfGraph): Set<RdfTriple> = cache.use(graph) { state ->
            check(!state.released) { "a released state was handed out" }
            state.triples
        }
    }

    /**
     * A repository with snapshot transactions. Stamps are never reused: a write inside a transaction gives the
     * writing thread a new private stamp, a commit or a rollback gives everyone a new committed stamp.
     */
    private class FakeRepository {
        private class Transaction(val content: HashMap<String, List<RdfTriple>>, var stamp: Long?)

        private val stamps = AtomicLong()
        @Volatile private var committedStamp = stamps.incrementAndGet()
        private val committed = ConcurrentHashMap<String, List<RdfTriple>>()
        private val transaction = ThreadLocal<Transaction?>()
        @Volatile var closed = false
        val reads = AtomicInteger()

        fun begin() {
            transaction.set(Transaction(HashMap(committed), null))
        }

        fun write(name: String, triples: List<RdfTriple>) {
            val open = checkNotNull(transaction.get()) { "no transaction" }
            open.content[name] = triples
            open.stamp = stamps.incrementAndGet()
        }

        fun commit() {
            val open = checkNotNull(transaction.get()) { "no transaction" }
            transaction.remove()
            if (open.stamp != null) {
                committed.putAll(open.content)
                committedStamp = stamps.incrementAndGet()
            }
        }

        fun rollback() {
            val open = transaction.get()
            transaction.remove()
            if (open?.stamp != null) committedStamp = stamps.incrementAndGet()
        }

        fun set(name: String, triples: List<RdfTriple>) {
            begin()
            write(name, triples)
            commit()
        }

        fun stamp(): Long {
            check(!closed) { "the repository is closed" }
            return transaction.get()?.stamp ?: committedStamp
        }

        fun hasUncommittedWrites(): Boolean = transaction.get()?.stamp != null

        fun content(name: String): List<RdfTriple> {
            reads.incrementAndGet()
            return (transaction.get()?.content ?: committed)[name].orEmpty()
        }

        /** A new handle of the graph [name], as `repository.getGraph(name)` returns one for every call. */
        fun graph(name: String, marked: Boolean): RdfGraph = if (marked) MarkedHandle(this, name) else Handle(this, name)
    }

    private open class Handle(val repository: FakeRepository, val name: String) : VersionedRdfGraph {
        override val modificationStamp: Long get() = repository.stamp()
        override fun getTriples(): List<RdfTriple> = repository.content(name)
        override fun hasTriple(triple: RdfTriple): Boolean = triple in repository.content(name)
        override fun size(): Int = repository.content(name).size
        override fun equals(other: Any?): Boolean =
            other is Handle && other.javaClass == javaClass && other.repository === repository && other.name == name
        override fun hashCode(): Int = System.identityHashCode(repository) * 31 + name.hashCode()
    }

    /** A handle whose provider tells when the stamp is private to the calling thread's transaction. */
    private class MarkedHandle(repository: FakeRepository, name: String) : Handle(repository, name), TransactionScopedGraph {
        override val isStampTransactionPrivate: Boolean get() = repository.hasUncommittedWrites()
    }

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "writer").apply { isDaemon = true } }

    @AfterEach
    fun stopWriter() {
        writer.shutdownNow()
    }

    private fun <T> onWriter(step: () -> T): T = writer.submit(Callable { step() }).get(30, TimeUnit.SECONDS)

    private fun everyVariant(test: (marked: Boolean, exclusive: Boolean) -> Unit) {
        for (marked in listOf(true, false)) {
            for (exclusive in listOf(true, false)) {
                test(marked, exclusive)
            }
        }
    }

    @Test
    fun `a writer is served its uncommitted content and every other thread the committed content`() {
        everyVariant { marked, exclusive ->
            val what = "marked=$marked exclusive=$exclusive"
            val f = Fixture(exclusive)
            val repo = FakeRepository()
            repo.set("g", listOf(triple(1)))
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), what)

            // The committed state is cached; the writer must not be served it once it has written.
            onWriter {
                repo.begin()
                repo.write("g", listOf(triple(1), triple(2)))
                assertEquals(setOf(triple(1), triple(2)), f.contentOf(repo.graph("g", marked)), "writer, $what")
            }
            // Whatever the writer left in the cache must not be served to another thread.
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), "reader, $what")
            onWriter { assertEquals(setOf(triple(1), triple(2)), f.contentOf(repo.graph("g", marked)), "writer again, $what") }
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), "reader again, $what")
            assertEquals(1, f.cache.size, "one graph, one cached state: $what")

            onWriter {
                repo.write("g", listOf(triple(3)))
                assertEquals(setOf(triple(3)), f.contentOf(repo.graph("g", marked)), "writer after a second write, $what")
                repo.commit()
            }
            assertEquals(setOf(triple(3)), f.contentOf(repo.graph("g", marked)), "reader after the commit, $what")
            onWriter { assertEquals(setOf(triple(3)), f.contentOf(repo.graph("g", marked)), "writer after the commit, $what") }
            assertEquals(1, f.cache.size, what)
            f.cache.close()
        }
    }

    @Test
    fun `a rolled back transaction leaves nothing of its content in the cache`() {
        everyVariant { marked, exclusive ->
            val what = "marked=$marked exclusive=$exclusive"
            val f = Fixture(exclusive)
            val repo = FakeRepository()
            repo.set("g", listOf(triple(1)))
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), what)
            onWriter {
                repo.begin()
                repo.write("g", emptyList())
                assertEquals(emptySet<RdfTriple>(), f.contentOf(repo.graph("g", marked)), "writer, $what")
                repo.rollback()
                assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), "writer after the rollback, $what")
            }
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked)), "reader after the rollback, $what")
            f.cache.close()
        }
    }

    @Test
    fun `a state built for a transaction-private stamp is never cached and is released when its use ends`() {
        for (exclusive in listOf(true, false)) {
            val f = Fixture(exclusive)
            val repo = FakeRepository()
            repo.set("g", listOf(triple(1)))
            f.contentOf(repo.graph("g", marked = true))
            assertEquals(1, f.cache.loadCount)

            val private = onWriter {
                repo.begin()
                repo.write("g", listOf(triple(1), triple(2)))
                f.cache.use(repo.graph("g", marked = true)) { it }
            }
            assertEquals(setOf(triple(1), triple(2)), private.triples)
            assertTrue(private.released, "it can never be hit again once the transaction ends: it must not be kept")
            assertEquals(listOf(private), f.released.toList(), "the committed state stays cached")
            assertEquals(1, f.cache.size)
            assertEquals(1, f.cache.count(GraphStateCache.Event.TEMPORARY))

            // The committed state was not touched: other threads still hit it without reading the graph.
            val readsBefore = repo.reads.get()
            assertEquals(setOf(triple(1)), f.contentOf(repo.graph("g", marked = true)))
            assertEquals(readsBefore, repo.reads.get(), "a hit of the committed state reads nothing")
            assertEquals(2, f.cache.loadCount)

            onWriter { repo.commit() }
            assertEquals(setOf(triple(1), triple(2)), f.contentOf(repo.graph("g", marked = true)))
            assertEquals(3, f.cache.loadCount, "the committed change is loaded once")
            assertEquals(1, f.cache.size)
            f.cache.close()
        }
    }

    @Test
    fun `a transaction that wrote another graph shares the cached state of an unchanged graph`() {
        everyVariant { marked, exclusive ->
            val what = "marked=$marked exclusive=$exclusive"
            val f = Fixture(exclusive)
            val repo = FakeRepository()
            repo.set("g", listOf(triple(1)))
            val committedState = f.cache.use(repo.graph("g", marked)) { it }

            // The stamp is repository-wide: the writer's stamp of "g" moved although "g" did not change.
            val writerState = onWriter {
                repo.begin()
                repo.write("other", listOf(triple(9)))
                f.cache.use(repo.graph("g", marked)) { it }
            }
            assertTrue(writerState === committedState, "the content is the same: no second state is built ($what)")
            assertEquals(1, f.cache.loadCount, what)

            val readsBefore = repo.reads.get()
            assertTrue(f.cache.use(repo.graph("g", marked)) { it } === committedState, what)
            assertEquals(readsBefore, repo.reads.get(), "the committed stamp still hits without a read ($what)")
            assertEquals(emptyList<State>(), f.released.toList(), what)
            onWriter { repo.rollback() }
            f.cache.close()
        }
    }

    @Test
    fun `the state of a graph whose stamp read fails is released at once`() {
        for (marked in listOf(true, false)) {
            val f = Fixture(exclusive = true)
            val repo = FakeRepository()
            repo.set("g", listOf(triple(1)))
            repo.set("h", listOf(triple(2)))
            val state = f.cache.use(repo.graph("g", marked)) { it }
            val other = FakeRepository().also { it.set("g", listOf(triple(3))) }
            f.contentOf(other.graph("g", marked))

            repo.closed = true
            // A new, equal handle: the copy of the closed repository's graph is found by handle equality.
            val failure = assertThrows(IllegalStateException::class.java) { f.cache.use(repo.graph("g", marked)) { } }
            assertEquals("the repository is closed", failure.message)
            assertTrue(state.released, "the copy of a closed repository must not wait for eviction or memory pressure")
            assertEquals(listOf(state), f.released.toList(), "only that graph's state is released")
            assertEquals(1, f.cache.size)
            // A handle the cache has never seen fails the same way and releases nothing.
            assertThrows(IllegalStateException::class.java) { f.cache.use(repo.graph("h", marked)) { } }
            assertEquals(1, f.released.size)
            f.cache.close()
        }
    }
}
