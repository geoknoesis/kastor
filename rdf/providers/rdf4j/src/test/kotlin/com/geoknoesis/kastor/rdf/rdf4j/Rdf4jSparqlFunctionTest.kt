package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MAX_FUNCTION_DEPTH
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.string
import org.eclipse.rdf4j.query.QueryInterruptedException
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Custom SPARQL functions (SHACL 1.2 SPARQL Extensions, section 7) are visible to the one sandboxed query that carries
 * them; their bodies run nested sandboxed queries on the calling query's dataset, and every error is a SPARQL error.
 * The same cases as the Jena provider's `JenaSparqlFunctionTest`, run on RDF4J.
 */
class Rdf4jSparqlFunctionTest {

    private val data = Rdf.parse("<urn:a> <urn:l> \"x\"@en , \"y\"@en , \"z\"@de .", RdfFormat.TURTLE).getTriples()

    private val repository: RdfRepository = Rdf4jProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(data)
    }

    @AfterEach fun close() {
        repository.close()
        // Nothing is ever registered globally.
        listOf("urn:spaced", "urn:count", "urn:self", "urn:f").forEach { assertFalse(FunctionRegistry.getInstance().has(it), it) }
    }

    private val spaced = SparqlFunction("urn:spaced", listOf(false, false)) { call ->
        string("${(call.arguments[0] as Literal).lexical} ${(call.arguments[1] as Literal).lexical}")
    }

    private val count = SparqlFunction("urn:count", listOf(false, true)) { call ->
        val bindings = mapOf("s" to call.arguments[0]!!) + (call.arguments[1]?.let { mapOf("lang" to it) } ?: emptyMap())
        call.select(
            SandboxedSelectQuery(
                "SELECT (COUNT(?l) AS ?n) WHERE { ?s <urn:l> ?l FILTER langMatches(lang(?l), COALESCE(?lang, 'en')) }"
            ),
            bindings,
        ) { rows -> rows.single().get("n") }
    }

    private val library = SparqlFunctionLibrary.of(listOf(spaced, count))

    /** The value of `?v` in each row, with [library], on the untimed and on the timed path (which must agree). */
    private fun values(sparql: String, functions: SparqlFunctionLibrary = library, on: RdfRepository = repository): List<RdfTerm?> {
        val untimed = on.withSelectRows(SandboxedSelectQuery(sparql, functions)) { rows -> rows.map { it.get("v") }.toList() }
        val timed = on.withSelectRows(SandboxedSelectQuery(sparql, functions), emptyMap(), Duration.ofSeconds(30)) { rows ->
            rows.map { it.get("v") }.toList()
        }
        assertEquals(untimed, timed)
        return untimed
    }

    private fun value(sparql: String, functions: SparqlFunctionLibrary = library): RdfTerm? = values(sparql, functions).single()

    private fun lexical(sparql: String, functions: SparqlFunctionLibrary = library): String? =
        (value(sparql, functions) as Literal?)?.lexical

    @Test
    fun `library functions are called with evaluated arguments`() {
        assertEquals("John Doe", lexical("SELECT ?v WHERE { BIND (<urn:spaced>('John', 'Doe') AS ?v) }"))
        assertEquals("2", lexical("SELECT ?v WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }"))
        assertEquals("1", lexical("SELECT ?v WHERE { BIND (<urn:count>(<urn:a>, 'de') AS ?v) }"))
        // Arguments are evaluated per solution.
        assertEquals("x x", lexical("SELECT ?v WHERE { <urn:a> <urn:l> ?l FILTER (lang(?l) = 'en' && str(?l) = 'x') BIND (<urn:spaced>(str(?l), str(?l)) AS ?v) }"))
    }

    @Test
    fun `errors follow SPARQL semantics`() {
        assertNull(value("SELECT ?v WHERE { BIND (<urn:count>(?unbound) AS ?v) }"))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:spaced>('a') AS ?v) }"))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:spaced>('a', 'b', 'c') AS ?v) }"))
        // A failing body is an error too, and so is a body that returns nothing.
        val failing = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:boom", emptyList()) { throw IllegalStateException("boom") },
            SparqlFunction("urn:none", emptyList()) { null },
        ))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:boom>() AS ?v) }", failing))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:none>() AS ?v) }", failing))
        // In a FILTER, an error is false.
        assertEquals(emptyList<RdfTerm?>(), values("SELECT ?v WHERE { BIND (1 AS ?v) FILTER (<urn:boom>()) }", failing))
    }

    @Test
    fun `a failing body runs once per call`() {
        // RDF4J folds calls with constant arguments while it optimizes the query; a body must not run again at
        // evaluation when that first call failed.
        val calls = AtomicInteger()
        val failing = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:boom", listOf(false)) { calls.incrementAndGet(); null }))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:boom>('a') AS ?v) }", failing))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a declaration cannot shadow a built-in function`() {
        val hijack = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("http://www.w3.org/2005/xpath-functions#concat", listOf(true, true)) { string("hijacked") },
        ))
        assertEquals(
            "ab",
            lexical("PREFIX fn: <http://www.w3.org/2005/xpath-functions#> SELECT ?v WHERE { BIND (fn:concat('a', 'b') AS ?v) }", hijack),
        )
        // Also when the arguments are not constants (RDF4J does not fold the call).
        assertEquals(
            "xx",
            lexical("PREFIX fn: <http://www.w3.org/2005/xpath-functions#> SELECT ?v WHERE { <urn:a> <urn:l> ?l FILTER (str(?l) = 'x') BIND (fn:concat(str(?l), str(?l)) AS ?v) }", hijack),
        )
    }

    @Test
    fun `nested queries stay sandboxed`() {
        val refusals = AtomicInteger()
        val remote = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:remote", emptyList()) { call ->
                try {
                    // (RDF4J's parser drops a SERVICE with an empty group, so this one has a pattern.)
                    call.select(SandboxedSelectQuery("SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?x ?p ?o } }"), emptyMap()) { rows ->
                        rows.firstOrNull()?.get("x")
                    }
                } catch (e: IllegalArgumentException) {
                    // Refused statically, before any request.
                    refusals.incrementAndGet()
                    throw e
                }
            },
        ))
        assertEquals(listOf<RdfTerm?>(null), values("SELECT ?v WHERE { BIND (<urn:remote>() AS ?v) }", remote))
        assertEquals(2, refusals.get())
    }

    @Test
    fun `recursion ends at the depth limit`() {
        val calls = AtomicInteger()
        lateinit var recursive: SparqlFunctionLibrary
        recursive = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:self", emptyList()) { call ->
                calls.incrementAndGet()
                call.select(SandboxedSelectQuery("SELECT (<urn:self>() AS ?x) {}", recursive), emptyMap()) { rows ->
                    rows.single().get("x")
                }
            },
        ))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:self>() AS ?v) }", recursive))
        // Bodies run in the top query and in nested queries 1 to MAX_FUNCTION_DEPTH, on each of the two paths.
        assertEquals(2 * (MAX_FUNCTION_DEPTH + 1), calls.get())
    }

    @Test
    fun `a nested query never outlives the timeout of the calling query`() {
        // The deadline is measured with the repository's clock, which only moves when the body says so: the nested
        // query starts after the deadline, so it is interrupted, and the interruption stops the calling query instead
        // of becoming an unbound value (RDF4J's own time limit, on real time, never fires within the minute given).
        val now = AtomicLong()
        (repository as Rdf4jRepository).clock = now::get
        val late = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:late", emptyList()) { call ->
                now.addAndGet(Duration.ofMinutes(2).toNanos())
                call.select(SandboxedSelectQuery("SELECT ?l WHERE { <urn:a> <urn:l> ?l } LIMIT 1"), emptyMap()) { rows ->
                    rows.single().get("l")
                }
            },
        ))
        val query = SandboxedSelectQuery("SELECT ?v WHERE { BIND (<urn:late>() AS ?v) }", late)
        val failure = assertThrows(RdfQueryException::class.java) {
            repository.withSelectRows(query, emptyMap(), Duration.ofMinutes(1)) { rows -> rows.map { it.get("v") }.toList() }
        }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is QueryInterruptedException }, failure.toString())
        // Without a timeout, the same query has no deadline.
        assertEquals(1, repository.withSelectRows(query) { rows -> rows.map { it.get("v") }.toList() }.filterNotNull().size)
    }

    @Test
    fun `a nested query sees only its own functions`() {
        // The calling query's library has <urn:spaced>; the nested query is sent without a library.
        val nested = "SELECT (<urn:spaced>('a', 'b') AS ?x) {}"
        fun outer(nestedFunctions: SparqlFunctionLibrary) = SparqlFunctionLibrary.of(listOf(
            spaced,
            SparqlFunction("urn:outer", emptyList()) { call ->
                call.select(SandboxedSelectQuery(nested, nestedFunctions), emptyMap()) { rows -> rows.single().get("x") ?: string("unseen") }
            },
        ))
        val sparql = "SELECT ?v WHERE { BIND (<urn:outer>() AS ?v) }"
        assertEquals("unseen", lexical(sparql, outer(SparqlFunctionLibrary.EMPTY)))
        assertEquals("a b", lexical(sparql, outer(library)))
    }

    @Test
    fun `a java function IRI in a nested query loads no class`() {
        // RDF4J resolves functions from its registry only; a java: IRI is an unknown function, so an error.
        val nested = "SELECT ?x WHERE { BIND (<java:java.lang.Runtime>(1) AS ?x) }"
        val probing = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:probe", emptyList()) { call ->
                call.select(SandboxedSelectQuery(nested), emptyMap()) { rows -> rows.single().get("x") ?: string("unseen") }
            },
        ))
        assertEquals("unseen", lexical("SELECT ?v WHERE { BIND (<urn:probe>() AS ?v) }", probing))
    }

    @Test
    fun `nested queries read the dataset of the calling query`() {
        // Outside GRAPH a nested query reads the default graph only; inside GRAPH it reads the named graphs, even when
        // the calling query reads no named graph.
        repository.editGraph(Iri("urn:g")).addTriples(Rdf.parse("<urn:a> <urn:l> \"w\"@en .", RdfFormat.TURTLE).getTriples())
        val graphs = SparqlFunctionLibrary.of(listOf(
            count,
            SparqlFunction("urn:named", emptyList()) { call ->
                call.select(SandboxedSelectQuery("SELECT (COUNT(?l) AS ?n) WHERE { GRAPH ?g { <urn:a> <urn:l> ?l } }"), emptyMap()) { rows ->
                    rows.single().get("n")
                }
            },
        ))
        assertEquals("2", lexical("SELECT ?v WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }", graphs))
        assertEquals("1", lexical("SELECT ?v WHERE { BIND (<urn:named>() AS ?v) }", graphs))
    }

    @Test
    fun `nested queries read the store of every variant`(@TempDir dir: Path) {
        val sparql = "SELECT ?v WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }"
        listOf(
            Rdf4jProvider().createRepository("memory-rdfs", RdfConfig()),
            Rdf4jProvider().createRepository("memory-shacl", RdfConfig()),
            Rdf4jRepository.NativeRepository(dir.resolve("native").toString()),
            Rdf4jRepository.NativeRdfsRepository(dir.resolve("native-rdfs").toString()),
        ).forEach { store ->
            store.use {
                it.editDefaultGraph().addTriples(data)
                assertEquals("2", (values(sparql, library, it).single() as Literal).lexical, it.toString())
            }
        }
    }

    @Test
    fun `concurrent queries never see each other's functions`() {
        // Both libraries define <urn:f>; each body waits until the other query is inside its own body too.
        val inside = CountDownLatch(2)
        fun library(result: String) = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:f", emptyList()) {
                inside.countDown()
                inside.await(10, TimeUnit.SECONDS)
                string(result)
            },
        ))
        val sparql = "SELECT ?v WHERE { BIND (<urn:f>() AS ?v) }"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = listOf("one", "two").map { result ->
                val functions = library(result)
                pool.submit<String?> {
                    (repository.withSelectRows(SandboxedSelectQuery(sparql, functions)) { rows -> rows.single().get("v") } as Literal?)?.lexical
                }
            }
            assertEquals(listOf("one", "two"), runs.map { it.get(30, TimeUnit.SECONDS) })
            assertEquals(0L, inside.count)
        } finally {
            pool.shutdownNow()
        }
        // Afterwards, the same query on this thread sees no function.
        assertNull(value(sparql, SparqlFunctionLibrary.EMPTY))
    }

    @Test
    fun `functions are per query`() {
        val sparql = "SELECT ?v WHERE { BIND (<urn:spaced>('John', 'Doe') AS ?v) }"
        assertEquals("John Doe", lexical(sparql))
        // A sandboxed query without the function: an unknown function is an error, so the variable is unbound (as on Jena).
        assertNull(value(sparql, SparqlFunctionLibrary.EMPTY))
        assertNull(value("SELECT ?v WHERE { BIND (<urn:nowhere>() AS ?v) }", SparqlFunctionLibrary.EMPTY))
        // An ordinary query keeps RDF4J's behaviour: it fails on the unknown function (Jena leaves the variable unbound).
        assertThrows(RdfQueryException::class.java) {
            repository.withSelectRows(SparqlSelectQuery(sparql)) { rows -> rows.single().get("v") }
        }
    }

    @Test
    fun `no global registration`() {
        assertEquals("John Doe", lexical("SELECT ?v WHERE { BIND (<urn:spaced>('John', 'Doe') AS ?v) }"))
        assertFalse(FunctionRegistry.getInstance().has("urn:spaced"))
    }
}
