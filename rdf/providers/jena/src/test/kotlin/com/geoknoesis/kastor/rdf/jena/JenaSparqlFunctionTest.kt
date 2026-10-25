package com.geoknoesis.kastor.rdf.jena

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
import org.apache.jena.query.QueryCancelledException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Custom SPARQL functions (SHACL 1.2 SPARQL Extensions, section 7) are visible to the one sandboxed query that carries
 * them; their bodies run nested sandboxed queries on the calling query's dataset, and every error is a SPARQL error.
 */
class JenaSparqlFunctionTest {

    private val repository: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(
            Rdf.parse("<urn:a> <urn:l> \"x\"@en , \"y\"@en , \"z\"@de .", RdfFormat.TURTLE).getTriples()
        )
    }

    @AfterEach fun close() = repository.close()

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
    private fun values(sparql: String, functions: SparqlFunctionLibrary = library): List<RdfTerm?> {
        val untimed = repository.withSelectRows(SandboxedSelectQuery(sparql, functions)) { rows -> rows.map { it.get("v") }.toList() }
        val timed = repository.withSelectRows(SandboxedSelectQuery(sparql, functions), emptyMap(), Duration.ofSeconds(30)) { rows ->
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
    fun `a declaration cannot shadow a built-in function`() {
        val hijack = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("http://www.w3.org/2005/xpath-functions#concat", listOf(true, true)) { string("hijacked") },
        ))
        assertEquals(
            "ab",
            lexical("PREFIX fn: <http://www.w3.org/2005/xpath-functions#> SELECT ?v WHERE { BIND (fn:concat('a', 'b') AS ?v) }", hijack),
        )
    }

    @Test
    fun `nested queries stay sandboxed`() {
        val refusals = AtomicInteger()
        val remote = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:remote", emptyList()) { call ->
                try {
                    call.select(SandboxedSelectQuery("SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> {} }"), emptyMap()) { rows ->
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
        // query starts after the deadline, so it is cancelled, and the cancellation stops the calling query instead
        // of becoming an unbound value (Jena's own timer, on real time, never fires within the minute given).
        val now = AtomicLong()
        (repository as JenaRepository).hooks.clock = now::get
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
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is QueryCancelledException }, failure.toString())
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
    fun `a nested query cannot compute a function IRI, so it cannot load a java class`() {
        val probe = "java:" + SandboxProbeFunction::class.java.name
        val (head, tail) = probe.substring(0, 5) to probe.substring(5)
        val nested = "SELECT ?x WHERE { BIND (CALL(IRI(CONCAT(\"$head\", \"$tail\")), 1) AS ?x) }"
        val probing = SparqlFunctionLibrary.of(listOf(
            SparqlFunction("urn:probe", emptyList()) { call ->
                call.select(SandboxedSelectQuery(nested), emptyMap()) { rows -> rows.single().get("x") ?: string("unseen") }
            },
        ))
        val before = SandboxProbeFunction.instances.get()
        // CALL is refused in a sandboxed query: the nested query fails, so the body fails (a SPARQL error, ?v
        // unbound) before anything is evaluated.
        assertEquals(null, lexical("SELECT ?v WHERE { BIND (<urn:probe>() AS ?v) }", probing))
        assertEquals(before, SandboxProbeFunction.instances.get())
    }

    @Test
    // The nested query runs inside the read transaction of the calling query. (TDB2 files stay mapped on Windows: no cleanup.)
    fun `nested queries read the store of every variant`(@TempDir(cleanup = CleanupMode.NEVER) dir: Path) {
        val data = Rdf.parse("<urn:a> <urn:l> \"x\"@en , \"y\"@en , \"z\"@de .", RdfFormat.TURTLE).getTriples()
        val sparql = "SELECT ?v WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }"
        listOf(
            JenaProvider().createRepository("memory-inference", RdfConfig()),
            JenaRepository.Tdb2Repository(dir.toString()),
        ).forEach { store ->
            store.use {
                it.editDefaultGraph().addTriples(data)
                assertEquals("2", (it.withSelectRows(SandboxedSelectQuery(sparql, library)) { rows -> rows.single().get("v") } as Literal).lexical)
            }
        }
    }

    @Test
    fun `nested queries run on the dataset of the calling query`() {
        repository.editGraph(Iri("urn:g")).addTriples(Rdf.parse("<urn:a> <urn:l> \"w\"@en .", RdfFormat.TURTLE).getTriples())
        repository.editGraph(Iri("urn:h")).addTriples(
            Rdf.parse("<urn:a> <urn:l> \"v\"@en , \"u\"@en , \"t\"@en .", RdfFormat.TURTLE).getTriples()
        )
        fun from(graph: String) = SparqlFunction("urn:from-${graph.substringAfter(':')}", emptyList()) { call ->
            call.select(SandboxedSelectQuery("SELECT (COUNT(?l) AS ?n) FROM <$graph> WHERE { <urn:a> <urn:l> ?l }"), emptyMap()) { rows ->
                rows.single().get("n")
            }
        }
        val graphs = SparqlFunctionLibrary.of(listOf(count, from("urn:g"), from("urn:h")))
        // Without dataset clauses, the nested query reads Kastor's default graph.
        assertEquals("2", lexical("SELECT ?v WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }", graphs))
        // The calling query reads <urn:g> as its default graph: so does the nested query.
        assertEquals("1", lexical("SELECT ?v FROM <urn:g> WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }", graphs))
        // A nested FROM selects among the graphs of the calling query's dataset; any other graph reads as empty.
        assertEquals("3", lexical("SELECT ?v WHERE { BIND (<urn:from-h>() AS ?v) }", graphs))
        assertEquals("0", lexical("SELECT ?v FROM <urn:g> WHERE { BIND (<urn:from-h>() AS ?v) }", graphs))
        assertEquals("3", lexical("SELECT ?v FROM <urn:g> FROM NAMED <urn:h> WHERE { BIND (<urn:from-h>() AS ?v) }", graphs))
        // Only the named graphs of the calling query can be selected: its default graph has no name there.
        assertEquals("0", lexical("SELECT ?v FROM <urn:g> WHERE { BIND (<urn:from-g>() AS ?v) }", graphs))
        // A calling query with FROM NAMED only has an empty default graph, and so has the nested query.
        assertEquals("0", lexical("SELECT ?v FROM NAMED <urn:h> WHERE { BIND (<urn:count>(<urn:a>) AS ?v) }", graphs))
    }

    @Test
    fun `a timeout too long for nanoseconds means no deadline`() {
        val rows = repository.withSelectRows(SandboxedSelectQuery("SELECT ?v WHERE { BIND (1 AS ?v) }", library), emptyMap(), Duration.ofMillis(Long.MAX_VALUE)) {
            it.toList()
        }
        assertEquals(1, rows.size)
    }

    @Test
    fun `an interrupted body cancels the calling query and keeps the interrupt`() {
        val interrupted = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:wait", emptyList()) { throw InterruptedException("stop") }))
        val failure = assertThrows(RdfQueryException::class.java) {
            repository.withSelectRows(SandboxedSelectQuery("SELECT ?v WHERE { BIND (<urn:wait>() AS ?v) }", interrupted)) { rows ->
                rows.map { it.get("v") }.toList()
            }
        }
        // Thread.interrupted() also clears the flag for the next tests on this thread.
        assertTrue(Thread.interrupted(), "the interrupt flag is restored")
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is QueryCancelledException }, failure.toString())
    }

    @Test
    fun `a cancellation wrapped in another exception still stops the query`() {
        val wrapping = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:wrap", emptyList()) {
            throw RuntimeException("wrapped", QueryCancelledException())
        }))
        val failure = assertThrows(RdfQueryException::class.java) {
            repository.withSelectRows(SandboxedSelectQuery("SELECT ?v WHERE { BIND (<urn:wrap>() AS ?v) }", wrapping)) { rows ->
                rows.map { it.get("v") }.toList()
            }
        }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is QueryCancelledException }, failure.toString())
    }

    @Test
    fun `functions are per query`() {
        val sparql = "SELECT ?v WHERE { BIND (<urn:spaced>('John', 'Doe') AS ?v) }"
        assertEquals("John Doe", lexical(sparql))
        assertNull(value(sparql, SparqlFunctionLibrary.EMPTY))
        assertNull(repository.withSelectRows(SparqlSelectQuery(sparql)) { rows -> rows.single().get("v") })
    }
}
