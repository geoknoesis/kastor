package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlSelect
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository
import org.eclipse.rdf4j.sail.inferencer.fc.SchemaCachingRDFSInferencer
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [Rdf4jRepository] that wraps an RDF4J repository it did not create (a plain Sail, an inferencer over one, a
 * remote SPARQL endpoint, a repository wrapper) cannot hold a [SandboxedSelectQuery] to its sandbox: the store's own
 * evaluation strategy would call every function of RDF4J's global registry, and a remote store would evaluate the
 * untrusted text with its server's functions. Such a query is refused with [UnsupportedOperationException] on every
 * entry point before anything is evaluated or sent, as `SparqlRepository` refuses it; trusted queries still run.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class Rdf4jWrappedRepositorySandboxTest {

    private val global = "urn:kastor:test:wrapped-global:" + java.util.UUID.randomUUID()
    private val calls = AtomicInteger()
    private val counting = object : Function {
        override fun getURI() = global
        override fun evaluate(valueFactory: ValueFactory, vararg args: Value): Value {
            calls.incrementAndGet()
            return valueFactory.createLiteral("called")
        }
    }

    @AfterEach fun unregister() {
        FunctionRegistry.getInstance().remove(counting)
    }

    private val entryPoints: List<Pair<String, (RdfRepository, SparqlSelect) -> List<BindingSet>>> = listOf(
        "select" to { repo, query -> repo.select(query).toList() },
        "withSelectRows" to { repo, query -> repo.withSelectRows(query) { it.toList() } },
        "timed withSelectRows" to { repo, query -> repo.withSelectRows(query, emptyMap(), Duration.ofSeconds(10)) { it.toList() } },
    )

    private fun foreignStores(): List<Pair<String, () -> Repository>> = listOf(
        "plain memory sail" to { SailRepository(MemoryStore()) },
        "RDFS inferencer over a plain sail" to { SailRepository(SchemaCachingRDFSInferencer(MemoryStore())) },
        "repository wrapper around a plain sail" to { RepositoryWrapper(SailRepository(MemoryStore())) },
    )

    @Test
    fun `a sandboxed query on a store Kastor did not create is refused before evaluation`() {
        FunctionRegistry.getInstance().add(counting)
        val sparql = "SELECT ?x WHERE { BIND (<$global>(1) AS ?x) }"
        for ((store, create) in foreignStores()) {
            val repo = Rdf4jRepository(create().also { it.init() })
            try {
                for ((name, call) in entryPoints) {
                    assertThrows(UnsupportedOperationException::class.java, { call(repo, SandboxedSelectQuery(sparql)) }, "$store / $name")
                }
                assertEquals(0, calls.get(), "$store: the global function ran inside a sandboxed query")
                // A trusted query is the caller's own: it still runs, with the store's functions.
                assertEquals(1, repo.withSelectRows(SparqlSelectQuery(sparql)) { it.toList() }.size, store)
                assertTrue(calls.get() > 0, store)
                calls.set(0)
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun `a sandboxed query is never sent to a remote SPARQL endpoint`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val contacts = AtomicInteger()
            val acceptor = Thread {
                while (!server.isClosed) {
                    try { server.accept().close(); contacts.incrementAndGet() } catch (_: java.io.IOException) { }
                }
            }.apply { isDaemon = true; start() }
            val repo = Rdf4jRepository(SPARQLRepository("http://127.0.0.1:${server.localPort}/sparql").also { it.init() })
            try {
                val query = SandboxedSelectQuery("SELECT ?x WHERE { BIND (<http://jena.apache.org/ARQ/function#wait>(2147483647) AS ?x) }")
                for ((name, call) in entryPoints) {
                    assertThrows(UnsupportedOperationException::class.java, { call(repo, query) }, name)
                }
            } finally {
                repo.close()
                server.close()
                acceptor.join(10_000)
            }
            assertEquals(0, contacts.get(), "the untrusted query was sent to the remote endpoint")
        }
    }

    @Test
    fun `stores the factory methods create still evaluate sandboxed queries`() {
        for (repo in listOf(Rdf4jRepository.MemoryRepository(), Rdf4jRepository.MemoryRdfsRepository(), Rdf4jRepository.MemoryShaclRepository())) {
            repo.use {
                for ((name, call) in entryPoints) {
                    assertEquals(1, call(it, SandboxedSelectQuery("SELECT ?x WHERE { BIND (1 AS ?x) }")).size, name)
                }
            }
        }
    }
}

/**
 * The backstop behind the refusal: a sandboxed evaluation that no Kastor strategy claimed (so ran without the sandbox)
 * fails, and what it returned is closed.
 */
class Rdf4jFunctionScopeBackstopTest {

    @Test
    fun `an unclaimed scope fails the query and closes its result`() {
        val closed = AtomicInteger()
        val result = object : AutoCloseable {
            override fun close() {
                closed.incrementAndGet()
            }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            // Typed, so that the block returns the result (not Unit, the type assertThrows' lambda would infer).
            val returned: AutoCloseable = Rdf4jFunctionScope.with(com.geoknoesis.kastor.rdf.SparqlFunctionLibrary.EMPTY, 0,
                Long.MAX_VALUE, System::nanoTime, { _, _, _ -> error("no nested query") }, null) { result }
            returned.close()
        }
        assertEquals(1, closed.get())
        // A claimed scope returns the result untouched.
        val claimed = Rdf4jFunctionScope.with(com.geoknoesis.kastor.rdf.SparqlFunctionLibrary.EMPTY, 0, Long.MAX_VALUE, System::nanoTime,
            { _, _, _ -> error("no nested query") }, null) { Rdf4jFunctionScope.claim(); result }
        assertTrue(claimed === result)
        assertEquals(1, closed.get())
        assertEquals(null, Rdf4jFunctionScope.claim())
    }
}
