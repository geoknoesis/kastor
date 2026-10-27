package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round sixteen, providers P1-3: a sandboxed query had no memory budget across its rows. Each computed value was held
 * to 16 Mi characters, but a 1 KB chain of `CONCAT(?x, ?x)` over 50 triples kept about 10 Mi characters per row
 * (through `DISTINCT`, `ORDER BY` or `GROUP BY`) and ran out of a 256 MiB heap in under a second on both providers.
 * The reviewer's query and its variants run here in a child JVM with a 256 MiB heap and a 1 s timeout: each must stop
 * with the sandbox's own [SandboxLimitException] (the query-wide budget of computed characters), never with an
 * `OutOfMemoryError` and not by running into the timeout.
 *
 * Round sixteen, security review: functions that copy a part of an argument (`SUBSTR`, `STRBEFORE`, `STRAFTER`)
 * allocate it all the same, so they are charged too (over a large literal of the data); and the nested queries of a
 * custom function share the budget of the query that calls it instead of each starting a fresh one.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SandboxedMemoryContractTest {

    @Test
    fun `chained CONCATs stop at the query-wide budget on both providers, in a bounded heap`() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val process = ProcessBuilder(
            java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), SandboxedMemoryProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(240, TimeUnit.SECONDS), "the probe JVM did not end: $output")
        assertEquals(0, process.exitValue(), output)
        val lines = output.lines().filter { it.startsWith("PROBE ") }
        println(lines.joinToString("\n"))
        val probes = SandboxedMemoryProbe.QUERIES.size + SandboxedMemoryProbe.COPYING_QUERIES.size + 1
        assertEquals(SandboxedMemoryProbe.PROVIDERS.size * probes, lines.size, output)
        for (line in lines) {
            val (_, provider, query, outcome) = line.split('\t')
            assertEquals("limit", outcome, "$provider $query\n$output")
        }
        // Jena's process-wide NodeValue cache (10,000 entries) kept the large computed strings of every query.
        val retained = output.lines().filter { it.startsWith("RETAINED") }
        assertEquals(SandboxedMemoryProbe.PROVIDERS.size, retained.size, output)
        println(retained.joinToString("\n"))
        for (line in retained) {
            val (_, provider, megabytes) = line.split('\t')
            assertTrue(megabytes.toLong() < 64, "$provider: $megabytes MB of heap still in use after its queries\n$output")
        }
    }

    @Test
    fun `queries that compute little are not affected by the budget`() {
        val queries = listOf(
            "SELECT (GROUP_CONCAT(CONCAT(STR(?s), ?o); SEPARATOR=\",\") AS ?g) { ?s <urn:p> ?o }",
            "SELECT ?s (UCASE(CONCAT(?o, ?o)) AS ?u) { ?s <urn:p> ?o } ORDER BY DESC(?u) ?s",
            "SELECT DISTINCT (REPLACE(STR(?s), \"s\", \"t\") AS ?r) { { SELECT ?s { ?s <urn:p> ?o } } }",
        )
        for ((name, factory) in SandboxedMemoryProbe.PROVIDERS) {
            factory().use { repo ->
                SandboxedMemoryProbe.load(repo)
                for (sparql in queries) {
                    val rows = repo.withSelectRows(SandboxedSelectQuery(sparql, SparqlFunctionLibrary.of(emptyList()))) { it.count() }
                    assertTrue(rows > 0, "$name: $sparql")
                }
            }
        }
    }
}

/** The reviewer's heap probes and their variants, run in a child JVM by [SandboxedMemoryContractTest]. */
object SandboxedMemoryProbe {
    val PROVIDERS: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )

    /** `BIND(CONCAT(?x0, ?x0) AS ?x1) ... ?x[n]`, from `?x0` = `CONCAT(STR(?o), STR(?s))`. */
    private fun chain(n: Int) =
        "?s <urn:p> ?o BIND(CONCAT(STR(?o), STR(?s)) AS ?x0) " + (1..n).joinToString(" ") { "BIND(CONCAT(?x${it - 1}, ?x${it - 1}) AS ?x$it)" }

    val QUERIES: List<String> = listOf(
        // The reviewer's query (qheap4) and its ORDER BY and DISTINCT forms (qheap, qheap5).
        "SELECT (COUNT(DISTINCT ?x20) AS ?c) { ${chain(20)} }",
        "SELECT ?s ?x20 { ${chain(20)} } ORDER BY ?x20",
        "SELECT * { ${chain(20)} } ORDER BY ?x20",
        "SELECT DISTINCT ?x20 { ${chain(20)} }",
        "SELECT (MAX(?x20) AS ?m) (COUNT(DISTINCT ?x20) AS ?c) { ${chain(20)} }",
        // GROUP BY and GROUP_CONCAT, one group per subject.
        "SELECT ?s (SAMPLE(?x20) AS ?v) { ${chain(20)} } GROUP BY ?s",
        "SELECT ?s (GROUP_CONCAT(?x19) AS ?g) { ${chain(19)} } GROUP BY ?s",
        // Materialised in nested subqueries.
        "SELECT (COUNT(*) AS ?c) { { SELECT DISTINCT ?x20 { { SELECT ?s ?x20 { ${chain(20)} } ORDER BY ?x20 } } } }",
        // REPLACE multiplying its text at the last step: every "b" becomes 512.
        "SELECT ?s ?x15 { ${chain(14)} BIND(REPLACE(?x14, \"b\", \"${"b".repeat(512)}\") AS ?x15) } ORDER BY ?x15",
    )

    /** 50 triples `<urn:s{i}> <urn:p> "ab"`, the reviewer's `d50.ttl`. */
    fun load(repo: RdfRepository) {
        repo.editDefaultGraph().addTriples((0 until 50).map { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), string("ab")) })
    }

    /** Each binds ten 1 Mi-character copies of the large literal `?b` per row, over 200 rows, and keeps them. */
    val COPYING_QUERIES: List<String> = listOf(
        { i: Int -> "SUBSTR(?b, ${i + 1})" }, { _: Int -> "STRAFTER(?b, \"a\")" }, { _: Int -> "STRBEFORE(?b, \"z\")" },
    ).map { f -> "SELECT * { ?s <urn:p> ?o . <urn:big> <urn:q> ?b ${(1..10).joinToString(" ") { "BIND(${f(it)} AS ?y$it)" }} } ORDER BY ?s" }

    /** A custom function whose nested query computes a chain of 16 Mi characters from its argument, called per row. */
    private val grow = SparqlFunction("urn:kastor:grow", listOf(false)) { call ->
        val sparql = "SELECT (STRLEN(?x20) AS ?n) { BIND(CONCAT(STR(?x), \"urn:s00\") AS ?x0) " +
            (1..20).joinToString(" ") { "BIND(CONCAT(?x${it - 1}, ?x${it - 1}) AS ?x$it)" } + " }"
        call.select(SandboxedSelectQuery(sparql), mapOf("x" to call.arguments[0]!!)) { rows -> rows.single().get("n") }
    }

    /** Calls [grow] once per row: its nested queries share the budget of this query. */
    const val NESTED_QUERY = "SELECT ?s (<urn:kastor:grow>(?o) AS ?n) { ?s <urn:p> ?o }"

    /** 200 triples on `<urn:p>`, and `<urn:big> <urn:q> "ab..bz"` of 1 Mi characters. */
    private fun loadLarge(repo: RdfRepository) {
        repo.editDefaultGraph().addTriples(
            (0 until 200).map { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), string("ab")) } +
                RdfTriple(Iri("urn:big"), Iri("urn:q"), string("a" + "b".repeat(1024 * 1024) + "z")),
        )
    }

    private fun probe(name: String, repo: RdfRepository, sparql: String, library: SparqlFunctionLibrary = SparqlFunctionLibrary.of(emptyList())) {
        val started = System.nanoTime()
        val outcome = try {
            repo.withSelectRows(SandboxedSelectQuery(sparql, library), emptyMap(), Duration.ofSeconds(1)) { it.count() }
            "rows"
        } catch (e: Exception) {
            val chain = generateSequence<Throwable>(e) { it.cause }.toList()
            if (chain.any { it is SandboxLimitException }) "limit"
            else "error:${chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }}"
        } catch (e: Throwable) {
            "error:${e.javaClass.name}"
        }
        println("PROBE \t$name\t${sparql.take(60)}\t$outcome\t${(System.nanoTime() - started) / 1_000_000}")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        for ((name, factory) in PROVIDERS) {
            factory().use { repo ->
                load(repo)
                QUERIES.forEach { probe(name, repo, it) }
                probe(name, repo, NESTED_QUERY, SparqlFunctionLibrary.of(listOf(grow)))
            }
            factory().use { repo ->
                loadLarge(repo)
                COPYING_QUERIES.forEach { probe(name, repo, it) }
            }
            // Nothing a query computed may outlive it: the repositories are closed, so what is left is a cache.
            System.gc()
            Runtime.getRuntime().let { println("RETAINED\t$name\t${(it.totalMemory() - it.freeMemory()) shr 20}") }
        }
    }
}
