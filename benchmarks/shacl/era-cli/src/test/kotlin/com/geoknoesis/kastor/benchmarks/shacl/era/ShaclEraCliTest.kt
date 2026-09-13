package com.geoknoesis.kastor.benchmarks.shacl.era

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaclEraCliTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `stdout contains exactly the two ERA timing lines with sub-millisecond precision`() {
        val data = dir.resolve("data.ttl")
        Files.writeString(data, "@prefix ex: <http://example.org/> .\nex:a a ex:Thing .\n")
        val shapes = dir.resolve("shapes.ttl")
        Files.writeString(
            shapes,
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """.trimIndent(),
        )
        val report = dir.resolve("report.ttl")
        val buffer = ByteArrayOutputStream()
        PrintStream(buffer, true, Charsets.UTF_8).use { runEraBenchmark(data.toString(), shapes.toString(), report.toString(), it) }

        val lines = buffer.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size, "unexpected stdout: $lines")
        assertTrue(Regex("""Load time: \d+\.\d{6}""").matches(lines[0]), lines[0])
        assertTrue(Regex("""Validation time: \d+\.\d{6}""").matches(lines[1]), lines[1])
        assertTrue(Files.readString(report).contains("ValidationReport"))
    }

    @Test
    fun `seconds keep microsecond precision`() {
        assertEquals("0.000250", formatSeconds(250_000))
        assertEquals("1.234568", formatSeconds(1_234_567_890))
    }
}
