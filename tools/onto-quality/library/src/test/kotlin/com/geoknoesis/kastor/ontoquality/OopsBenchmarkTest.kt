package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.math.roundToLong
import kotlin.system.measureNanoTime

/**
 * Micro-benchmark for `onto-quality` against the bundled **OOPS!-style corpus** (`oops-corpus/`):
 * timings for **OWL_QUALITY** (structural SHACL) and **EMBEDDING_QUALITY** (semantic SHACL on enriched graphs).
 *
 * Latency-only; pitfall-ID calibration vs OOPS! is enforced by [OopsCalibrationTest].
 *
 * Enable: **`KASTOR_OOPS_BENCHMARK=1`** (PowerShell: `$env:KASTOR_OOPS_BENCHMARK='1'`).
 *
 * Optional JVM properties:
 * * `kastor.oops.benchmark.iterations` (default **10**) — structural measurement loops over the corpus
 * * `kastor.oops.benchmark.warmup` (default **2**) — structural warmup rounds before timing
 * * `kastor.oops.benchmark.embeddingIterations` (default **3**)
 * * `kastor.oops.benchmark.embeddingWarmup` (default **1**)
 *
 * Semantic tests are skipped when **`KASTOR_SKIP_EMBEDDING_TESTS=1`**.
 */
@EnabledIfEnvironmentVariable(named = "KASTOR_OOPS_BENCHMARK", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OopsBenchmarkTest {

    private val structuralChecker by lazy {
        QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.OWL_QUALITY).build()
    }

    private fun corpusFixturePaths(): List<String> =
        listOf(
            "oops-corpus/P02.ttl",
            "oops-corpus/P03.ttl",
            "oops-corpus/P04.ttl",
            "oops-corpus/P06.ttl",
            "oops-corpus/P08.ttl",
            "oops-corpus/P09.ttl",
            "oops-corpus/P11.ttl",
            "oops-corpus/P12.ttl",
            "oops-corpus/P13.ttl",
            "oops-corpus/P19.ttl",
            "oops-corpus/P20.ttl",
            "oops-corpus/P21.ttl",
            "oops-corpus/P22_M1.ttl",
            "oops-corpus/P22_M2.ttl",
            "oops-corpus/P22_M3.ttl",
            "oops-corpus/P22_M4.ttl",
            "oops-corpus/P24.ttl",
            "oops-corpus/P25.ttl",
            "oops-corpus/P26.ttl",
            "oops-corpus/P27.ttl",
            "oops-corpus/P32.ttl",
            "oops-corpus/P33.ttl",
            "oops-corpus/P34.ttl",
            "oops-corpus/P35.ttl",
            "oops-corpus/P36.ttl",
            "oops-corpus/P38.ttl",
            "oops-corpus/P39.ttl",
            "oops-corpus/P40.ttl",
            "oops-corpus/P41.ttl",
        ).sorted()

    private data class ParsedFixture(val resourcePath: String, val graph: RdfGraph)

    private fun loadCorpus(paths: Collection<String>): List<ParsedFixture> =
        paths.mapNotNull { path ->
            val url = javaClass.classLoader.getResource(path) ?: return@mapNotNull null
            ParsedFixture(path, url.openStream().use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) })
        }

    private fun roundMs(nanos: Double): Double =
        ((nanos / 1_000_000.0 * 100.0).roundToLong() / 100.0)

    @Test
    @Order(1)
    fun `benchmark structural OWL quality against full bundled OOPS corpus`() {
        val available = loadCorpus(corpusFixturePaths())
        assumeFalse(available.isEmpty(), "No oops-corpus/*.ttl entries on classpath — cannot benchmark.")

        val iterations =
            System.getProperty("kastor.oops.benchmark.iterations", "10").toInt().coerceAtLeast(1)
        val warmup =
            System.getProperty("kastor.oops.benchmark.warmup", "2").toInt().coerceAtLeast(0)

        repeat(warmup) {
            available.forEach { structuralChecker.check(it.graph) }
        }

        val perFixtureNanos = MutableList(available.size) { mutableListOf<Long>() }
        repeat(iterations) {
            available.forEachIndexed { idx, entry ->
                val nanos = measureNanoTime { structuralChecker.check(entry.graph) }
                perFixtureNanos[idx].add(nanos)
            }
        }

        val summary =
            buildString {
                appendLine()
                appendLine(
                    "=== onto-quality — OWL_QUALITY (structural) vs bundled OOPS! TTL corpus ===",
                )
                appendLine(
                    "Fixtures: ${available.size} TTL | warmup corpus rounds: $warmup | timed iterations: $iterations",
                )
                appendLine("| fixture | triples | avg_check_ms | findings_final |")
                appendLine("|---------|---------:|-------------:|---------------:|")

                var totalNanos = 0L
                available.forEachIndexed { idx, entry ->
                    val samples = perFixtureNanos[idx]
                    val avgNanos = samples.sum().toDouble() / samples.size
                    totalNanos += samples.sum()
                    appendLine(
                        "| ${entry.resourcePath.substringAfter("oops-corpus/")} | " +
                            "${entry.graph.size()} | " +
                            "${roundMs(avgNanos)} | " +
                            "${structuralChecker.check(entry.graph).findings.size} |",
                    )
                }
                val invokes = iterations.toLong() * available.size
                val totalMs = totalNanos.toDouble() / 1_000_000.0
                val meanPer =
                    if (invokes > 0) "${"%.4f".format(totalMs / invokes)} ms"
                    else "n/a"
                val throughput =
                    if (totalMs > 0 && invokes > 0) "${"%.1f".format(invokes * 1000.0 / totalMs)} checks/s (approx.)"
                    else "n/a"
                appendLine()
                appendLine(
                    "Σ timed SHACL: ${"%.3f".format(totalMs)} ms over $invokes validations",
                )
                appendLine("Mean per validation: $meanPer · $throughput")
            }
        println(summary)
    }

    @Test
    @Order(2)
    @DisabledIfEnvironmentVariable(named = "KASTOR_SKIP_EMBEDDING_TESTS", matches = "1")
    fun `benchmark semantic embedding catalogue against bundled OOPS fixtures`() {
        val parsed =
            loadCorpus(
                listOf(
                    "oops-corpus/P02.ttl",
                    "oops-corpus/P12.ttl",
                    "oops-corpus/P21.ttl",
                    "oops-corpus/P32.ttl",
                ),
            )
        assumeFalse(parsed.isEmpty(), "Semantic benchmark needs classpath fixtures.")

        val enricher = SemanticEnricher.default()
        lateinit var enrichedDocs: List<ParsedFixture>
        val enrichmentNanos =
            measureNanoTime {
                enrichedDocs =
                    parsed.map {
                        ParsedFixture(it.resourcePath, enricher.enrich(it.graph))
                    }
            }

        val checker =
            QualityChecker.builder(ShaclValidation.validator())
                .addCatalog(BundledCatalogs.EMBEDDING_QUALITY)
                .build()

        val iterations =
            System.getProperty("kastor.oops.benchmark.embeddingIterations", "3").toInt().coerceAtLeast(1)
        val warmup =
            System.getProperty("kastor.oops.benchmark.embeddingWarmup", "1").toInt().coerceAtLeast(0)

        repeat(warmup) {
            enrichedDocs.forEach { checker.check(it.graph) }
        }

        val perNanos = MutableList(enrichedDocs.size) { mutableListOf<Long>() }
        repeat(iterations) {
            enrichedDocs.forEachIndexed { idx, doc ->
                perNanos[idx].add(measureNanoTime { checker.check(doc.graph) })
            }
        }

        val summary =
            buildString {
                appendLine()
                appendLine(
                    "=== onto-quality — EMBEDDING_QUALITY (semantic tier) vs OOPS semantic fixtures ===",
                )
                appendLine(
                    "Embedding enrich wall (cold, one pass over ${enrichedDocs.size} graphs): " +
                        "${"%.3f".format(enrichmentNanos / 1_000_000.0)} ms",
                )
                appendLine(
                    "Warmup SHACL corpus rounds: $warmup · timed iterations: $iterations",
                )
                appendLine("| fixture | triples | avg_check_ms | findings_final |")
                appendLine("|---------|---------:|-------------:|---------------:|")

                var sumSamples = 0L
                enrichedDocs.forEachIndexed { idx, entry ->
                    val samples = perNanos[idx]
                    val avgNano = samples.sum().toDouble() / samples.size
                    sumSamples += samples.sum()
                    appendLine(
                        "| ${entry.resourcePath.substringAfter("oops-corpus/")} | " +
                            "${entry.graph.size()} | ${roundMs(avgNano)} | " +
                            "${checker.check(entry.graph).findings.size} |",
                    )
                }
                val invokes = iterations.toLong() * enrichedDocs.size
                val meanSemantic =
                    if (invokes > 0) "${"%.4f".format(sumSamples.toDouble() / 1e6 / invokes)} ms"
                    else "n/a"
                appendLine()
                appendLine(
                    "Σ timed SHACL (semantic bundle): ${"%.3f".format(sumSamples / 1_000_000.0)} ms · " +
                        "mean/check: $meanSemantic",
                )
            }
        println(summary)
    }
}
