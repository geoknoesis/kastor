package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.rdf.RdfGraph
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks made before paid LLM calls, redaction and bounds of `--debug` output, and the summary of unexplained findings. */
class CliSafetyTest {
    @TempDir lateinit var dir: Path

    /** OOPS P26: an owl:SymmetricProperty with owl:inverseOf violates a `sh:Violation` shape of owl-quality. */
    private val violationTtl =
        """
        @prefix : <http://example.org/sym#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        :knows a owl:ObjectProperty , owl:SymmetricProperty ; owl:inverseOf :knownBy .
        :knownBy a owl:ObjectProperty .
        """.trimIndent()

    private fun ontology(): Path = dir.resolve("onto.ttl").also { it.writeText(violationTtl) }

    private class FakeEnricherFactory : PipelineEnricherFactory {
        var opened = 0

        override fun open(options: EmbeddingCliOptions): PipelineEnricher {
            opened++
            return object : PipelineEnricher {
                override fun enrich(ontology: RdfGraph): RdfGraph = ontology

                override fun close() = Unit
            }
        }
    }

    private fun env(key: String?): (String) -> String? =
        { name ->
            when (name) {
                LLM_EXPLAIN_ENV -> "true"
                LlmExplanationConfig.OPENAI_API_KEY -> key
                else -> null
            }
        }

    private class Run(val status: Int, val err: String)

    private fun run(argv: List<String>, environment: CliEnvironment): Run {
        val err = StringBuilder()
        val status = runOntoQa(argv, environment) { err.appendLine(it) }
        return Run(status, err.toString())
    }

    /** Runs the command with Clikt's test terminal, which captures what the command prints on stderr. */
    private fun cli(argv: List<String>, environment: CliEnvironment): Run {
        val result = ontoQualityApp(environment).test(argv)
        return Run(result.statusCode, result.stderr)
    }

    @Test
    fun `an output that cannot be written is refused before any LLM call or model load`() {
        val onto = ontology().toString()
        val directory = Files.createDirectory(dir.resolve("a-directory"))
        val plainFile = dir.resolve("plain.txt").also { it.writeText("x") }
        val bad =
            mapOf(
                directory.toString() to "is a directory",
                plainFile.resolve("sub/report.json").toString() to "is not a directory",
            )
        for ((output, reason) in bad) {
            var explainerCalls = 0
            val factory = FakeEnricherFactory()
            val enricher = QualityExplanationEnricher { report, _ -> explainerCalls++; ExplainedQualityReport(report, emptyList()) }
            val environment = CliEnvironment(enricherFactory = factory, explanationEnricherFactory = { enricher }, env = env("sk-test-0123456789"))
            for (argv in listOf(
                listOf("check", onto, "--catalog", "owl-quality", "--explain", "--output", output),
                listOf("pipeline", onto, "--catalog", "owl-quality", "--explain", "--output", output),
                listOf("enrich", onto, "--output", output),
                listOf("metrics", onto, "--output", output),
            )) {
                val result = run(argv, environment)
                assertEquals(EXIT_RUNTIME_ERROR, result.status, "$argv: ${result.err}")
                assertTrue(result.err.contains("Cannot write --output") && result.err.contains(reason), "$argv: ${result.err}")
            }
            assertEquals(0, explainerCalls, "the LLM was called although $output cannot be written")
            assertEquals(0, factory.opened, "a model was loaded although $output cannot be written")
        }
        assertTrue(directory.toFile().isDirectory && plainFile.toFile().readText() == "x")
        assertNull(outputProblem(dir.resolve("new/deep/report.json")), "missing parent directories are created later")
        assertNull(outputProblem(plainFile), "an existing file is replaced")
        assertFalse(dir.resolve("new").exists(), "the check must not create anything")
    }

    @Test
    fun `short API keys and URL credentials are redacted in --debug output`() {
        val key = "k3y!"
        val base = "http://alice:pw12@ollama.internal:11434"
        val failure = IllegalStateException("401 for key k3y! at $base/api/chat (user alice:pw12, password pw12); see https://bob:hunter2@proxy.example/x")
        val throwing = QualityExplanationEnricher { _, _ -> throw failure }
        val failing =
            QualityExplanationEnricher { report, _ ->
                ExplainedQualityReport(report, emptyList(), listOf(ExplanationFailure(emptyList(), "request to $base failed for key k3y!", failure)))
            }
        for (enricher in listOf(throwing, failing)) {
            val out = dir.resolve("report.json")
            val environment = CliEnvironment(explanationEnricherFactory = { enricher }, env = env(key))
            val result =
                cli(
                    listOf("check", ontology().toString(), "--catalog", "owl-quality", "--explain", "--debug", "--format", "json", "--ollama-base", base, "--output", out.toString()),
                    environment,
                )
            assertEquals(EXIT_FINDINGS, result.status, result.err)
            assertTrue(result.err.contains("IllegalStateException"), result.err)
            for (secret in listOf(key, "pw12", "alice", "hunter2")) {
                assertFalse(result.err.contains(secret), "'$secret' on stderr: ${result.err}")
                assertFalse(Files.readString(out).contains(secret), "'$secret' in the report")
            }
            assertTrue(result.err.contains("http://***@ollama.internal:11434/api/chat") && result.err.contains("https://***@proxy.example/x"), result.err)
        }
    }

    @Test
    fun `a --debug stack trace is bounded`() {
        val failure = IllegalStateException("m".repeat(50_000))
        failure.stackTrace = Array(5_000) { StackTraceElement("com.example.Deep", "call$it", "Deep.kt", it) }
        val throwing = QualityExplanationEnricher { _, _ -> throw failure }
        val result =
            cli(
                listOf("check", ontology().toString(), "--catalog", "owl-quality", "--explain", "--debug", "--output", dir.resolve("o.txt").toString()),
                CliEnvironment(explanationEnricherFactory = { throwing }, env = env("sk-test-0123456789")),
            )
        assertEquals(EXIT_FINDINGS, result.status)
        val lines = result.err.lines()
        assertTrue(lines.size <= MAX_DEBUG_TRACE_LINES + 10, "${lines.size} lines of trace")
        assertTrue(lines.all { it.length <= MAX_DEBUG_LINE_CHARS + 40 }, "longest line: ${lines.maxOf { it.length }}")
        assertTrue(result.err.contains("at com.example.Deep.call0") && result.err.contains("more stack trace line(s) not shown"), result.err.takeLast(400))
    }

    @Test
    fun `stderr counts the findings left unexplained, per reason`() {
        fun refs(n: Int, tag: Char): List<FindingRef> = (1..n).map { FindingRef(tag.toString().repeat(63) + it) }
        val skipped = "Not sent: the prompt for 4 findings has 130000 characters, which exceeds the limit of 120000 characters; use a smaller batch size"
        val failures =
            listOf(
                ExplanationFailure(refs(4, 'a'), skipped),
                ExplanationFailure(refs(3, 'b'), "LLM request failed after 3 attempt(s) (HTTP 503)"),
                ExplanationFailure(refs(4, 'c'), skipped),
            )
        val incomplete = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList(), failures) }
        val environment = CliEnvironment(explanationEnricherFactory = { incomplete }, env = env("sk-test-0123456789"))
        val argv = listOf("check", ontology().toString(), "--catalog", "rdf12-quality", "--explain", "--output", dir.resolve("o.txt").toString())

        val result = cli(argv, environment)
        assertEquals(EXIT_OK, result.status, result.err)
        val lines = result.err.lines().filter { it.isNotEmpty() }
        assertEquals("LLM explanations incomplete: 11 finding(s) not explained, for 2 reasons:", lines[0], result.err)
        assertEquals("  - 8 finding(s): $skipped", lines[1])
        assertEquals("  - 3 finding(s): LLM request failed after 3 attempt(s) (HTTP 503)", lines[2])
        assertTrue(lines[3].contains("--fail-on-explain-error"), "the exit status is explained: ${lines[3]}")
        assertEquals(4, lines.size, result.err)

        val strict = cli(argv + "--fail-on-explain-error", environment)
        assertEquals(EXIT_EXPLAIN_ERROR, strict.status, strict.err)
        assertEquals(3, strict.err.lines().filter { it.isNotEmpty() }.size, strict.err)
    }

    @Test
    fun `--llm-max-output-tokens reaches the LLM configuration`() {
        val configs = ArrayList<LlmExplanationConfig>()
        val enricher = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList()) }
        val environment = CliEnvironment(explanationEnricherFactory = { configs += it; enricher }, env = env("sk-test-0123456789"))
        val argv = listOf("check", ontology().toString(), "--catalog", "owl-quality", "--explain", "--output", dir.resolve("o.txt").toString())
        assertEquals(EXIT_FINDINGS, run(argv, environment).status)
        assertEquals(EXIT_FINDINGS, run(argv + listOf("--llm-max-output-tokens", "1234"), environment).status)
        assertEquals(listOf(LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS, 1234), configs.map { it.maxOutputTokens })
        assertEquals(EXIT_USAGE, run(argv + listOf("--llm-max-output-tokens", "0"), environment).status)
        assertEquals(2, configs.size)
    }

    @Test
    fun `an interactive console gets its own charset, redirected output is UTF-8`() {
        assertEquals(Charsets.UTF_8, standardStreamCharset(null))
        assertEquals(Charset.forName("IBM850"), standardStreamCharset(Charset.forName("IBM850")))
        // Whether this JVM has an interactive console depends on how the tests are run: both answers are valid.
        val console = interactiveConsoleCharset()
        assertEquals(console ?: Charsets.UTF_8, standardStreamCharset(console))
    }
}
