package com.geoknoesis.kastor.ontoquality.embed

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS

/** Opt-in repeatable lifecycle probe. Heap is recorded, not confused with native RSS. */
@EnabledIfEnvironmentVariable(named = "KASTOR_RUN_SOAK_TESTS", matches = "1")
class NativeResourceSoakTest {
    @Test fun `native model creation inference and close remain stable`() {
        val cycles = (System.getenv("KASTOR_SOAK_CYCLES")?.toInt() ?: 50).coerceAtLeast(20)
        val threads = ManagementFactory.getThreadMXBean()
        val memory = ManagementFactory.getMemoryMXBean()
        val rows = mutableListOf("cycle,elapsed_ms,heap_used_bytes,live_threads,pid,rss_bytes,lifecycle")
        val rssSamples = mutableListOf<Long>()
        val labels = MemoryGraph(List(16) {
            RdfTriple(Iri("https://example.org/resource/$it"), RDFS.label, string("Lifecycle stability probe $it"))
        })
        val start = System.nanoTime()
        var baselineThreads = 0
        repeat(cycles + 3) { cycle ->
            val lifecycle = if (cycle % 2 == 0) "model" else "default_enricher"
            if (cycle % 2 == 0) {
                OnnxEmbeddingModel.fromMiniLm().use { model ->
                    val vectors = model.embed(List(16) { "Lifecycle stability probe $it" })
                    assertEquals(16, vectors.size)
                    assertTrue(vectors.all { it.size == model.dimension && it.all(Float::isFinite) })
                }
            } else {
                val enricher = SemanticEnricher.default()
                enricher.use { assertTrue(it.enrichmentOnly(labels).size() > 0) }
                kotlin.test.assertFailsWith<IllegalStateException> { enricher.enrichmentOnly(labels) }
                enricher.close()
            }
            if (cycle == 2) baselineThreads = threads.threadCount
            val rss = if (cycle % 5 == 0 || cycle == cycles + 2) residentBytes() else -1L
            if (cycle >= 3 && rss > 0) rssSamples += rss
            rows += "$cycle,${(System.nanoTime() - start) / 1_000_000},${memory.heapMemoryUsage.used},${threads.threadCount},${ProcessHandle.current().pid()},$rss,$lifecycle"
        }
        val report = Path.of(System.getProperty("kastor.soak.report", "build/reports/native-soak.csv"))
        Files.createDirectories(report.parent)
        Files.write(report, rows)
        if (rssSamples.size >= 6) {
            val before = rssSamples.take(3).sorted()[1]
            val after = rssSamples.takeLast(3).sorted()[1]
            assertTrue(after - before < 256L * 1024 * 1024,
                "Native RSS grew by ${(after - before) / (1024 * 1024)} MiB; report=$report")
        }
        assertTrue(threads.threadCount <= baselineThreads + 2,
            "Thread growth after warmup: $baselineThreads -> ${threads.threadCount}; report=$report")
    }

    private fun residentBytes(): Long {
        val status = Path.of("/proc/self/status")
        if (Files.isRegularFile(status)) {
            val row = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return -1
            return row.trim().split(Regex("\\s+"))[1].toLong() * 1024
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "(Get-Process -Id ${ProcessHandle.current().pid()}).WorkingSet64").redirectErrorStream(true).start()
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) return -1
                return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim().toLong() else -1
            } finally { process.destroyForcibly() }
        }
        return -1
    }
}
