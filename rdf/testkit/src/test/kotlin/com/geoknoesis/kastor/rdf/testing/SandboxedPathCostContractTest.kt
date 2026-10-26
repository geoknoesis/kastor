package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round thirteen, providers P1-1: on Jena, a property-path sequence nested in `?`, `*`, `+` or `|` was collected in
 * full for one binding, past the timeout and out of heap (`(p/p/p/p/p/p)?` over a 20-node complete graph: 7.9 s
 * under a 1 s timeout; `(p x 8)?`, `(p x 6)*` and an alternative: `OutOfMemoryError` with `-Xmx1g`). RDF4J stopped the
 * same queries at about 1 s. The reviewer's probes run here on **both** providers, in a child JVM with a 256 MiB heap,
 * so an unbounded collection shows as an `OutOfMemoryError` instead of passing on a large test heap.
 *
 * Every probe must end by itself, well within a few seconds, with a result or with the sandbox's own error (its
 * deadline or a [com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException]); never with an `Error`.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SandboxedPathCostContractTest {

    @Test
    fun `nested path sequences stop at the timeout on both providers, in a bounded heap`() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val process = ProcessBuilder(
            java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), SandboxedPathProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(240, TimeUnit.SECONDS), "the probe JVM did not end: $output")
        assertEquals(0, process.exitValue(), output)
        val lines = output.lines().filter { it.startsWith("PROBE ") }
        println(lines.joinToString("\n"))
        assertEquals(SandboxedPathProbe.PROVIDERS.size * SandboxedPathProbe.QUERIES.size, lines.size, output)
        for (line in lines) {
            val (_, provider, query, outcome, millis) = line.split('\t')
            assertTrue(outcome == "rows" || outcome == "limit", "$provider $query: $outcome\n$output")
            assertTrue(millis.toLong() < 5_000, "$provider $query took $millis ms under a 1 s timeout\n$output")
        }
    }

    /** A closure along a long chain (an RDF list, say): Jena's path engine recurses once per node of the chain. */
    @Test
    fun `a closure along a long chain gives its solutions or the sandbox's error, never a stack overflow`() {
        val length = 200_000
        for ((name, factory) in SandboxedPathProbe.PROVIDERS) {
            factory().use { repo ->
                repo.editDefaultGraph().addTriples((0 until length).map { RdfTriple(Iri("urn:c$it"), Iri("urn:p"), Iri("urn:c${it + 1}")) })
                val query = SandboxedSelectQuery("SELECT ?o { <urn:c0> <urn:p>* ?o }", SparqlFunctionLibrary.of(emptyList()))
                val outcome = runCatching { repo.withSelectRows(query) { it.count() } }
                outcome.exceptionOrNull()?.let { error ->
                    val chain = generateSequence(error) { it.cause }.toList()
                    assertTrue(error is Exception && chain.any { it is com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException }, "$name: $error")
                }
                outcome.getOrNull()?.let { assertEquals(length + 1, it, name) }
            }
        }
    }

    @Test
    fun `metered paths give the same solutions as plain ones`() {
        // The meter changes nothing but the cost: every standard operator, nested, on a small graph.
        val queries = listOf(
            "SELECT ?o { <urn:n0> (<urn:p>/<urn:p>)? ?o }", "SELECT ?o { <urn:n0> (<urn:p>|^<urn:p>)* ?o }",
            "SELECT ?s ?o { ?s (<urn:p>/<urn:p>)+ ?o }", "SELECT ?o { <urn:n0> ((<urn:p>/<urn:p>)|<urn:q>) ?o }",
            "SELECT ?x { ?x <urn:p>* ?x }", "SELECT ?o { <urn:n0> !(<urn:q>|^<urn:p>) ?o }",
            "SELECT ?s { ?s (<urn:p>/<urn:p>)? <urn:n2> }",
        )
        for ((name, factory) in SandboxedPathProbe.PROVIDERS) {
            factory().use { repo ->
                SandboxedPathProbe.load(repo, nodes = 4)
                for (sparql in queries) {
                    val sandboxed = repo.withSelectRows(SandboxedSelectQuery(sparql, SparqlFunctionLibrary.of(emptyList()))) { rows -> rows.map { row -> row.getVariableNames().sorted().associateWith { row.get(it) }.toString() }.sorted().toList() }
                    val plain = repo.withSelectRows(com.geoknoesis.kastor.rdf.SparqlSelectQuery(sparql)) { rows -> rows.map { row -> row.getVariableNames().sorted().associateWith { row.get(it) }.toString() }.sorted().toList() }
                    assertEquals(plain, sandboxed, "$name: $sparql")
                }
            }
        }
    }
}

/** The reviewer's path probes, run in a child JVM by [SandboxedPathCostContractTest]. */
object SandboxedPathProbe {
    val PROVIDERS: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )

    private fun seq(n: Int) = (1..n).joinToString("/") { "<urn:p>" }

    val QUERIES: List<String> = listOf(
        "SELECT ?o { <urn:n0> (${seq(6)})? ?o }",
        "SELECT ?o { <urn:n0> (${seq(8)})? ?o }",
        "SELECT ?o { <urn:n0> (${seq(6)})* ?o }",
        "SELECT ?o { <urn:n0> (${seq(6)})+ ?o }",
        "SELECT ?o { <urn:n0> ((${seq(6)})|<urn:q>) ?o }",
        "SELECT ?o { <urn:n0> ((${seq(6)})|<urn:q>) ?o } LIMIT 1",
        "SELECT ?o { <urn:n0> ((${seq(4)})?/(${seq(4)})?)* ?o }",
    )

    /** A complete directed graph of [nodes] nodes on `<urn:p>` (with loops): 400 triples for 20 nodes. */
    fun load(repo: RdfRepository, nodes: Int = 20) {
        val triples = (0 until nodes).flatMap { a -> (0 until nodes).map { b -> RdfTriple(Iri("urn:n$a"), Iri("urn:p"), Iri("urn:n$b")) } } +
            RdfTriple(Iri("urn:n0"), Iri("urn:q"), Iri("urn:n1"))
        repo.editDefaultGraph().addTriples(triples)
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
                        val sandboxed = chain.any { it is com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException } ||
                            chain.any { "time" in it.message.orEmpty().lowercase() || "interrupt" in it.message.orEmpty().lowercase() || "cancel" in it.javaClass.name.lowercase() }
                        if (sandboxed) "limit" else "error:${chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }}"
                    } catch (e: Throwable) {
                        "error:${e.javaClass.name}"
                    }
                    println("PROBE \t$name\t$sparql\t$outcome\t${(System.nanoTime() - started) / 1_000_000}")
                }
            }
        }
    }
}
