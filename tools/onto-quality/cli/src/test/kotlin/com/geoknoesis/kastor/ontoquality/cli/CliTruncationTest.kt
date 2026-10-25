package com.geoknoesis.kastor.ontoquality.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `onto-qa` never drops findings silently: by default every finding is reported, whatever their number; with
 * `--max-findings` the report is cut, says so in every format and on stderr, and the run fails (exit 1), because a
 * truncated report cannot show that no finding at or above `--severity` was dropped.
 */
class CliTruncationTest {
    @TempDir lateinit var dir: Path

    private class Run(val status: Int, val err: String)

    private fun run(argv: List<String>): Run {
        val err = StringBuilder()
        val status = runOntoQa(argv, CliEnvironment()) { err.appendLine(it) }
        return Run(status, err.toString())
    }

    /** [classes] undocumented classes (two warnings each), then, when [cycle], an rdfs:subClassOf cycle (P06 violations). */
    private fun ontology(classes: Int, cycle: Boolean): Path {
        val body = buildString {
            appendLine("@prefix ex: <http://example.org/big#> .")
            appendLine("@prefix owl: <http://www.w3.org/2002/07/owl#> .")
            appendLine("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
            appendLine("<http://example.org/big> a owl:Ontology ; rdfs:label \"big\" ; rdfs:comment \"c\" .")
            for (i in 1..classes) appendLine("ex:C$i a owl:Class ; rdfs:subClassOf ex:Root .")
            appendLine("ex:Root a owl:Class ; rdfs:label \"Root\" ; rdfs:comment \"root\" .")
            if (cycle) {
                appendLine("ex:Bad a owl:Class ; rdfs:label \"Bad\" ; rdfs:comment \"b\" ; rdfs:subClassOf ex:Bad2 .")
                appendLine("ex:Bad2 a owl:Class ; rdfs:label \"Bad2\" ; rdfs:comment \"b\" ; rdfs:subClassOf ex:Bad .")
            }
        }
        return dir.resolve("big.ttl").also { it.writeText(body) }
    }

    private fun json(path: Path) = Json.parseToJsonElement(path.readText()).jsonObject

    @Test
    fun `by default every finding is reported and a violation after the first thousand fails the check`() {
        val input = ontology(classes = 1_200, cycle = true)
        val out = dir.resolve("report.json")
        val result = run(listOf("check", input.toString(), "--catalog", "owl-quality", "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_FINDINGS, result.status, result.err)
        val report = json(out)
        val findings = report.getValue("findings").jsonArray
        assertTrue(findings.size > 2_400, "expected every finding, got ${findings.size}")
        assertTrue(
            findings.count { it.jsonObject.getValue("severity").jsonPrimitive.content == "VIOLATION" } >= 2,
            "the subclass-cycle violations must be reported",
        )
        assertFalse(report.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(result.err.contains("truncated"), result.err)
        val text = ontoQualityApp().test(listOf("check", input.toString(), "--catalog", "owl-quality", "--output", dir.resolve("r.txt").toString()))
        assertEquals("", text.stderr)
    }

    @Test
    fun `--max-findings cuts the report, marks it in every format and fails closed`() {
        val input = ontology(classes = 30, cycle = false)
        assertEquals(EXIT_OK, run(listOf("check", input.toString(), "--catalog", "owl-quality")).status, "no violation without the cycle")

        val out = dir.resolve("report.json")
        val capped =
            ontoQualityApp().test(listOf("check", input.toString(), "--catalog", "owl-quality", "--max-findings", "10", "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_FINDINGS, capped.statusCode, "a truncated report must not pass the gate: ${capped.stderr}")
        // One notice, from onto-qa itself (the library's log line of the same event is silenced in the CLI).
        assertEquals(1, capped.stderr.lines().count { it.isNotBlank() }, capped.stderr)
        assertContains(capped.stderr, "truncated")
        assertContains(capped.stderr, "--max-findings 10")
        val report = json(out)
        assertTrue(report.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(10, report.getValue("findings").jsonArray.size)

        val text = dir.resolve("report.txt")
        run(listOf("check", input.toString(), "--catalog", "owl-quality", "--max-findings", "10", "--output", text.toString()))
        assertContains(text.readText(), "TRUNCATED")

        val markdown = dir.resolve("report.md")
        run(listOf("check", input.toString(), "--catalog", "owl-quality", "--max-findings", "10", "--format", "markdown", "--output", markdown.toString()))
        assertContains(markdown.readText(), "Report truncated")

        val turtle = dir.resolve("report.ttl")
        run(listOf("check", input.toString(), "--catalog", "owl-quality", "--max-findings", "10", "--format", "turtle", "--output", turtle.toString()))
        assertContains(turtle.readText(), "reportTruncated")
    }

    @Test
    fun `a --max-findings limit that is not reached changes nothing`() {
        val input = ontology(classes = 3, cycle = false)
        val out = dir.resolve("report.json")
        val result = run(listOf("check", input.toString(), "--catalog", "owl-quality", "--max-findings", "1000", "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_OK, result.status, result.err)
        assertFalse(json(out).getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `--max-findings must be positive`() {
        val input = ontology(classes = 1, cycle = false)
        assertEquals(EXIT_USAGE, run(listOf("check", input.toString(), "--max-findings", "0")).status)
        assertEquals(EXIT_USAGE, run(listOf("pipeline", input.toString(), "--max-findings", "-1")).status)
    }
}
