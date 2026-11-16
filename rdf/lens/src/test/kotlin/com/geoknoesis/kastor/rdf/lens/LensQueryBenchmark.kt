package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.BooleanPredicate
import org.geoknoesis.lens.CompareOperator
import org.geoknoesis.lens.Filters
import org.geoknoesis.lens.Lens
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.NetworkClosure
import org.geoknoesis.lens.NetworkDirection
import org.geoknoesis.lens.NetworkLens
import org.geoknoesis.lens.NetworkLensCompiler
import org.geoknoesis.lens.SparqlCompiler
import org.geoknoesis.lens.TabularDsl
import org.geoknoesis.lens.TabularLens
import org.geoknoesis.lens.Value
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Opt-in wall-clock comparison of compiled lens queries on in-memory providers.
 *
 * Run with `LENS_BENCHMARK=1` (optionally `LENS_BENCHMARK_NODES`, default 20000,
 * `LENS_BENCHMARK_PROVIDER` = jena or rdf4j, and `LENS_BENCHMARK_OUT` for a result file). It uses only the public compile
 * and bridge APIs, so the same file can time an older checkout for comparison.
 * In-memory stores on one machine: treat the numbers as relative, not absolute.
 */
@EnabledIfEnvironmentVariable(named = "LENS_BENCHMARK", matches = "1")
class LensQueryBenchmark {
    private fun ex(local: String) = "urn:bench:$local"
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val integer = "http://www.w3.org/2001/XMLSchema#integer"
    private val size = System.getenv("LENS_BENCHMARK_NODES")?.toInt() ?: 20_000
    private val limits = KastorLensProviderBridge.Limits(
        maxNodes = 10_000_000, maxEdges = 10_000_000, timeout = Duration.ofSeconds(45)
    )

    private fun load(repo: RdfRepository) {
        val random = Random(11)
        val graph = repo.editDefaultGraph()
        val nodes = (0 until size).map { Iri(ex("n$it")) }
        for ((i, node) in nodes.withIndex()) {
            graph.addTriple(RdfTriple(node, rdfType, Iri(ex("Company"))))
            if (i % 3 == 0) graph.addTriple(RdfTriple(node, rdfType, Iri(ex("Healthcare"))))
            if (i % 50 == 0) graph.addTriple(RdfTriple(node, rdfType, Iri(ex("Restricted"))))
            if (i % (size / 20) == 0) graph.addTriple(RdfTriple(node, rdfType, Iri(ex("Start"))))
            if (i % 10 != 0) {
                graph.addTriple(RdfTriple(node, Iri(ex("age")), lit(random.nextInt(1, 90).toString(), Iri(integer))))
            }
            graph.addTriple(RdfTriple(node, Iri(ex("tag")), Iri(ex(if (random.nextInt(4) == 0) "red" else "blue"))))
            repeat(3) {
                graph.addTriple(RdfTriple(node, Iri(ex("link")), nodes[random.nextInt(size)]))
            }
        }
    }

    private fun ageIs(op: CompareOperator, k: Int): BooleanPredicate =
        Filters.compare(Filters.path(ex("age")), op, Filters.constant(Value.TypedLiteral(k.toString(), integer)))

    /** One warm-up; a case slower than five seconds is reported from that single run. */
    private fun median(runs: Int, block: () -> Int): Pair<Double, Int> {
        val warmStart = System.nanoTime()
        var rows = block()
        val warm = (System.nanoTime() - warmStart) / 1e6
        if (warm > 5_000) return warm to rows
        val times = (0 until runs).map {
            val start = System.nanoTime()
            rows = block()
            (System.nanoTime() - start) / 1e6
        }.sorted()
        return times[times.size / 2] to rows
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun benchmark() {
        val company = LensDsl.type(ex("Company"))
        val healthcare = LensDsl.type(ex("Healthcare"))
        val adult = ageIs(CompareOperator.GT, 18)
        val senior = ageIs(CompareOperator.GE, 60)
        val nodeCases: List<Pair<String, Lens>> = listOf(
            "and: 3 lenses sharing a base" to
                LensDsl.and(company, LensDsl.filter(company, adult), LensDsl.filter(company, senior)),
            "or: 2 filters on one base" to
                LensDsl.or(LensDsl.filter(company, senior), LensDsl.filter(company, ageIs(CompareOperator.LT, 18))),
            "minus: base minus filtered base" to
                LensDsl.minus(company, LensDsl.filter(company, adult)),
            "mixed composition" to LensDsl.and(
                LensDsl.eq(company, ex("tag"), Value.Iri(ex("red"))),
                LensDsl.filter(LensDsl.and(company, healthcare), adult),
                LensDsl.minus(company, LensDsl.type(ex("Restricted")))
            ),
            "exists: linked to a senior healthcare company" to
                LensDsl.exists(company, ex("link"), LensDsl.filter(healthcare, senior)),
            "filter: 2-step sequence path" to LensDsl.filter(healthcare, Filters.compare(
                Filters.sequence(ex("link"), ex("age")), CompareOperator.GE,
                Filters.constant(Value.TypedLiteral("85", integer))
            )),
            "minus: linked to a restricted company" to
                LensDsl.minus(healthcare, LensDsl.exists(company, ex("link"), LensDsl.type(ex("Restricted"))))
        )
        val tag = TabularLens.Column("tag", ex("tag"))
        val tabularCases: List<Pair<String, TabularLens>> = listOf(
            "table: count" to TabularDsl.count(LensDsl.filter(company, adult)),
            "table: facet by tag" to TabularDsl.facet(LensDsl.filter(company, adult), ex("tag")),
            "table: avg age by tag" to TabularDsl.avg(healthcare, ex("age"), tag),
            "table: project 2 columns" to TabularDsl.project(
                LensDsl.filter(healthcare, senior), tag, TabularLens.Column("age", ex("age"))
            )
        )
        val seeds = LensDsl.type(ex("Start"))
        val networkCases: List<Pair<String, NetworkLens>> = listOf(
            "network unguarded OUT d3 traversed" to NetworkLens(seeds, ex("link"), 3),
            "network guarded OUT d3 traversed" to NetworkLens(seeds, ex("link"), 3, traversalNode = healthcare),
            "network guarded BOTH d2 traversed" to
                NetworkLens(seeds, ex("link"), 2, NetworkDirection.BOTH, traversalNode = healthcare),
            "network unguarded OUT d2 induced" to
                NetworkLens(seeds, ex("link"), 2, closure = NetworkClosure.INDUCED),
            "network guarded OUT d3 induced" to
                NetworkLens(seeds, ex("link"), 3, traversalNode = healthcare, closure = NetworkClosure.INDUCED)
        )

        val out = System.getenv("LENS_BENCHMARK_OUT")?.let { File(it) }
        val only = System.getenv("LENS_BENCHMARK_PROVIDER")
        val report = object {
            fun appendLine(line: String) {
                println(line)
                out?.appendText(line + "\n")
            }
        }
        report.appendLine("nodes=$size")
        val providers: List<Pair<String, () -> RdfRepository>> = listOf(
            "jena" to { JenaRepository.MemoryRepository() },
            "rdf4j" to { Rdf4jRepository.MemoryRepository() }
        )
        for ((provider, factory) in providers.filter { only == null || it.first == only }) {
            factory().use { repo ->
                load(repo)
                val bridge = KastorLensProviderBridge(repo)
                for ((name, lens) in nodeCases) {
                    val sparql = SparqlCompiler().select(lens)
                    val line = runCatching {
                        val (ms, rows) = median(5) { bridge.nodes(sparql, limits).size }
                        "%-8s %-46s %10.1f ms  rows=%d".format(provider, name, ms, rows)
                    }.getOrElse { "%-8s %-46s FAILED %s".format(provider, name, it.message?.take(80)) }
                    report.appendLine(line)
                }
                val tabular = KastorTabularLensExecutor(repo)
                for ((name, table) in tabularCases) {
                    val line = runCatching {
                        val (ms, rows) = median(5) {
                            tabular.execute(table, KastorTabularLensExecutor.Limits(maxRows = 100_000)).rows.size
                        }
                        "%-8s %-46s %10.1f ms  rows=%d".format(provider, name, ms, rows)
                    }.getOrElse { "%-8s %-46s FAILED %s".format(provider, name, it.message?.take(80)) }
                    report.appendLine(line)
                }
                for ((name, network) in networkCases) {
                    val compiled = NetworkLensCompiler().compile(network)
                    val line = runCatching {
                        val (ms, rows) = median(3) {
                            val graph = bridge.network(compiled.nodes, compiled.edges, limits)
                            graph.nodes.size * 1_000_000 + graph.edges.size
                        }
                        "%-8s %-46s %10.1f ms  nodes=%d edges=%d".format(
                            provider, name, ms, rows / 1_000_000, rows % 1_000_000
                        )
                    }.getOrElse { "%-8s %-46s FAILED %s".format(provider, name, it.message?.take(80)) }
                    report.appendLine(line)
                }
            }
        }
    }
}
