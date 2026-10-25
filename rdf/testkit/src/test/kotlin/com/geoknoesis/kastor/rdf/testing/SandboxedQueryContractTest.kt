package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The [SandboxedSelectQuery] contract on every query path of core: each provider repository and each kind of
 * [Dataset] view over it either honours the sandbox (it refuses `SERVICE` with [IllegalArgumentException] before
 * evaluating anything, and its custom functions are visible) or refuses the query outright with
 * [UnsupportedOperationException]. No path may run the query as a trusted one.
 *
 * The `SERVICE` target is a local listening socket, so a path that loses the sandbox is caught by the connection it
 * makes, whatever error it then reports; the socket is never answered, and no other network is used.
 */
class SandboxedQueryContractTest {

    private val g1 = Iri("urn:kastor:g1")
    private val n1 = Iri("urn:kastor:n1")
    private val value = string("from-function")
    private val library = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:kastor:f", emptyList()) { value }))

    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "memory" to { MemoryRepository(RdfConfig()) },
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )

    /** A way of querying a repository: the repository itself, or a [Dataset] view planned one way or another. */
    private class Path(val name: String, val usesGraph: Boolean, val open: (RdfRepository) -> SparqlQueryable)

    private val paths = listOf(
        Path("repository", usesGraph = false) { it },
        Path("in-place dataset (FROM clauses)", usesGraph = false) { repo ->
            Dataset {
                defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
                namedGraph(n1, repo, n1)
            }
        },
        Path("store-default dataset (GRAPH rewrite)", usesGraph = true) { repo -> Dataset { defaultGraph(repo) } },
        Path("materialized dataset", usesGraph = false) { repo ->
            Dataset {
                defaultGraph(repo)
                namedGraph(n1, Rdf.memory().also { it.editGraph(n1) }, n1)
            }
        },
    )

    /** The ways a SELECT reaches a queryable. */
    private val calls: Map<String, (SparqlQueryable, SparqlSelect) -> List<BindingSet>> = linkedMapOf(
        "select" to { q, query -> q.select(query).toList() },
        "withSelectRows" to { q, query -> q.withSelectRows(query) { it.toList() } },
        "timed withSelectRows" to { q, query ->
            q.withSelectRows(query, emptyMap(), Duration.ofSeconds(30)) { it.toList() }
        },
    )

    private fun cases(check: (String, Path, (SparqlQueryable, SparqlSelect) -> List<BindingSet>, RdfRepository) -> Unit) =
        providers.flatMap { (provider, factory) ->
            paths.flatMap { path ->
                calls.map { (callName, call) ->
                    DynamicTest.dynamicTest("$provider / ${path.name} / $callName") {
                        factory().use { repo -> check(provider, path, call, repo) }
                    }
                }
            }
        }

    @TestFactory
    fun `SERVICE in a sandboxed query is refused before anything is contacted`() =
        cases { _, path, call, repo ->
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                // Every connection is counted and closed at once, so a path that lost the sandbox fails fast.
                val contacts = AtomicInteger()
                val acceptor = Thread {
                    while (!server.isClosed) {
                        try { server.accept().close(); contacts.incrementAndGet() } catch (_: java.io.IOException) { }
                    }
                }.apply { isDaemon = true; start() }
                val target = "http://127.0.0.1:${server.localPort}/sparql"
                val pattern = "SERVICE <$target> { ?s ?p ?o }"
                val sparql = if (path.usesGraph) "SELECT * WHERE { GRAPH ?g { ?a ?b ?c } $pattern }"
                else "SELECT * WHERE { $pattern }"
                val error = runCatching { path.open(repo).use { call(it, SandboxedSelectQuery(sparql, library)) } }
                    .exceptionOrNull()
                server.close()
                acceptor.join(10_000)
                assertEquals(0, contacts.get(), "the SERVICE endpoint was contacted, so the sandbox was lost")
                assertTrue(
                    error is IllegalArgumentException || error is UnsupportedOperationException,
                    "expected a sandbox refusal, got ${error ?: "a result"}",
                )
            }
        }

    @TestFactory
    fun `custom functions of a sandboxed query stay visible, or the query is refused`() =
        cases { _, path, call, repo ->
            val graph = if (path.usesGraph) "OPTIONAL { GRAPH ?g { ?a ?b ?c } } " else ""
            val query = SandboxedSelectQuery("SELECT ?v WHERE { $graph BIND(<urn:kastor:f>() AS ?v) }", library)
            val rows = try {
                path.open(repo).use { call(it, query) }
            } catch (_: UnsupportedOperationException) {
                return@cases
            } catch (e: Throwable) {
                fail("the sandboxed query failed instead of running or being refused: $e")
            }
            assertEquals(listOf<RdfTerm?>(value), rows.map { it.get("v") })
        }
}
