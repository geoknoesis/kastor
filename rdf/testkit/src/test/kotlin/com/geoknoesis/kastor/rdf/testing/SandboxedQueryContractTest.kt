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
 * Which cases refuse is fixed ([mustRefuse]): the graph-only `memory` repository itself, an RDF4J repository over a
 * store Kastor did not create (except through a materialized dataset, which copies its graphs), and the timed
 * `withSelectRows` of every [Dataset] view. Every other case must run the query, so a regression that made a provider
 * refuse all sandboxed queries (which would break SHACL-SPARQL) fails here instead of passing as a refusal.
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
        // A store Kastor did not create: it cannot be held to the sandbox, so every sandboxed query is refused.
        WRAPPED to {
            Rdf4jRepository(org.eclipse.rdf4j.repository.sail.SailRepository(org.eclipse.rdf4j.sail.memory.MemoryStore()).also { it.init() })
        },
    )

    /**
     * A way of querying a repository: the repository itself, or a [Dataset] view planned one way or another. [open]
     * adds every other repository it creates to its second argument, which the test closes.
     */
    private class Path(val name: String, val open: (RdfRepository, MutableList<AutoCloseable>) -> SparqlQueryable)

    private val repositoryPath = Path("repository") { repo, _ -> repo }

    private val materializedPath = Path("materialized dataset") { repo, resources ->
        val other = Rdf.memory().also(resources::add)
        other.editGraph(n1)
        Dataset {
            defaultGraph(repo)
            namedGraph(n1, other, n1)
        }
    }

    private val paths = listOf(
        repositoryPath,
        Path("in-place dataset (FROM clauses)") { repo, _ ->
            Dataset {
                defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
                namedGraph(n1, repo, n1)
            }
        },
        Path("store-default dataset (GRAPH rewrite)") { repo, _ -> Dataset { defaultGraph(repo) } },
        materializedPath,
    )

    /** The cases that refuse a sandboxed query with [UnsupportedOperationException]; every other case runs it. */
    private fun mustRefuse(provider: String, path: Path, call: String): Boolean =
        (provider == "memory" && path === repositoryPath) || (provider == WRAPPED && path !== materializedPath) ||
            (call == TIMED && path !== repositoryPath)

    /** The ways a SELECT reaches a queryable. */
    private val calls: Map<String, (SparqlQueryable, SparqlSelect) -> List<BindingSet>> = linkedMapOf(
        "select" to { q, query -> q.select(query).toList() },
        "withSelectRows" to { q, query -> q.withSelectRows(query) { it.toList() } },
        TIMED to { q, query -> q.withSelectRows(query, emptyMap(), Duration.ofSeconds(30)) { it.toList() } },
    )

    /** One case: [refuses] says whether it must refuse; [run] runs a query on it and closes what it opened. */
    private class Case(val refuses: Boolean, val run: (SparqlSelect) -> List<BindingSet>)

    private fun cases(check: (Case) -> Unit) =
        providers.flatMap { (provider, factory) ->
            paths.flatMap { path ->
                calls.map { (callName, call) ->
                    DynamicTest.dynamicTest("$provider / ${path.name} / $callName") {
                        factory().use { repo ->
                            check(Case(mustRefuse(provider, path, callName)) { query ->
                                val resources = mutableListOf<AutoCloseable>()
                                try {
                                    path.open(repo, resources).use { call(it, query) }
                                } finally {
                                    resources.forEach { it.close() }
                                }
                            })
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `SERVICE in a sandboxed query is refused before anything is contacted`() =
        cases { case ->
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                // Every connection is counted and closed at once, so a path that lost the sandbox fails fast.
                val contacts = AtomicInteger()
                val acceptor = Thread {
                    while (!server.isClosed) {
                        try { server.accept().close(); contacts.incrementAndGet() } catch (_: java.io.IOException) { }
                    }
                }.apply { isDaemon = true; start() }
                val target = "http://127.0.0.1:${server.localPort}/sparql"
                // The GRAPH pattern makes the store-default dataset rewrite the query; it changes nothing elsewhere.
                val sparql = "SELECT * WHERE { OPTIONAL { GRAPH ?g { ?a ?b ?c } } SERVICE <$target> { ?s ?p ?o } }"
                val error = runCatching { case.run(SandboxedSelectQuery(sparql, library)) }.exceptionOrNull()
                server.close()
                acceptor.join(10_000)
                assertEquals(0, contacts.get(), "the SERVICE endpoint was contacted, so the sandbox was lost")
                if (case.refuses) {
                    assertTrue(error is UnsupportedOperationException, "expected a refusal of the query, got ${error ?: "a result"}")
                } else {
                    assertTrue(error is IllegalArgumentException, "expected the sandbox to refuse SERVICE, got ${error ?: "a result"}")
                }
            }
        }

    @TestFactory
    fun `custom functions of a sandboxed query stay visible, or the query is refused`() =
        cases { case ->
            val sparql = "SELECT ?v WHERE { OPTIONAL { GRAPH ?g { ?a ?b ?c } } BIND(<urn:kastor:f>() AS ?v) }"
            val outcome = runCatching { case.run(SandboxedSelectQuery(sparql, library)) }
            if (case.refuses) {
                assertTrue(outcome.exceptionOrNull() is UnsupportedOperationException, "expected a refusal, got $outcome")
                return@cases
            }
            val rows = outcome.getOrElse { fail("the sandboxed query failed instead of running: $it") }
            assertEquals(listOf<RdfTerm?>(value), rows.map { it.get("v") })
        }

    private companion object {
        const val TIMED = "timed withSelectRows"
        const val WRAPPED = "rdf4j (wrapped plain store)"
    }
}
