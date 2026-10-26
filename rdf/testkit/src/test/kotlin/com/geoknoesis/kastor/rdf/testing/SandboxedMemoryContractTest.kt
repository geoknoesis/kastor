package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
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
        assertEquals(SandboxedMemoryProbe.PROVIDERS.size * SandboxedMemoryProbe.QUERIES.size, lines.size, output)
        for (line in lines) {
            val (_, provider, query, outcome) = line.split('\t')
            assertEquals("limit", outcome, "$provider $query\n$output")
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

    @JvmStatic
    fun main(args: Array<String>) {
        for ((name, factory) in PROVIDERS) {
            factory().use { repo ->
                load(repo)
                for (sparql in QUERIES) {
                    val started = System.nanoTime()
                    val outcome = try {
                        repo.withSelectRows(SandboxedSelectQuery(sparql, SparqlFunctionLibrary.of(emptyList())), emptyMap(), Duration.ofSeconds(1)) { it.count() }
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
            }
        }
    }
}
