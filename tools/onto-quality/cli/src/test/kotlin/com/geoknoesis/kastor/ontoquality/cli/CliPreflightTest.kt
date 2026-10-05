package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Redaction of short secrets, provider limits said up front, and output problems found before any paid work. */
class CliPreflightTest {
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

    private fun env(openAi: String?, anthropic: String? = null): (String) -> String? =
        { name ->
            when (name) {
                LLM_EXPLAIN_ENV -> "true"
                LlmExplanationConfig.OPENAI_API_KEY -> openAi
                LlmExplanationConfig.ANTHROPIC_API_KEY -> anthropic
                else -> null
            }
        }

    private class Run(val status: Int, val err: String)

    /** Runs the command with the test terminal of Clikt, which captures what the command prints on stderr. */
    private fun cli(argv: List<String>, environment: CliEnvironment): Run {
        val result = ontoQualityApp(environment).test(argv)
        return Run(result.statusCode, result.stderr)
    }

    private fun failing(reason: String): QualityExplanationEnricher =
        QualityExplanationEnricher { report, _ ->
            ExplainedQualityReport(report, emptyList(), listOf(ExplanationFailure(listOf(FindingRef("a".repeat(64))), reason, IllegalStateException(reason))))
        }

    private fun checkArgs(vararg extra: String): List<String> =
        listOf("check", ontology().toString(), "--catalog", "owl-quality", "--explain", "--output", dir.resolve("o.txt").toString()) + extra

    @Test
    fun `a one-character API key does not mangle status codes or counts, and is flagged once`() {
        val reason = "LLM request failed after 1 attempt(s): HTTP 401 Unauthorized, key=1 rejected (11 of 12)"
        val result = cli(checkArgs("--debug"), CliEnvironment(explanationEnricherFactory = { failing(reason) }, env = env("1")))
        assertEquals(EXIT_FINDINGS, result.status, result.err)
        assertTrue(result.err.contains("LLM explanations incomplete: 1 finding(s) not explained"), result.err)
        assertTrue(result.err.contains("after 1 attempt(s): HTTP 401 Unauthorized, key=*** rejected (11 of 12)"), result.err)
        assertFalse(result.err.contains("***01") || result.err.contains("40***"), result.err)
        val warnings = result.err.lines().filter { it.contains("unusual for an API key") }
        assertEquals(1, warnings.size, result.err)
        assertTrue(warnings.single().contains("OPENAI_API_KEY") && warnings.single().contains("1 character"), warnings.single())
        assertFalse(Files.readString(dir.resolve("o.txt")).contains("40***"))
    }

    @Test
    fun `the key of a provider that is not in use is not a secret of the run`() {
        // The Anthropic variable holds a word of the tool's own messages; the run uses OpenAI.
        val environment = CliEnvironment(explanationEnricherFactory = { failing("HTTP 503 from provider") }, env = env("sk-test-0123456789", anthropic = "explanations"))
        val result = cli(checkArgs(), environment)
        assertEquals(EXIT_FINDINGS, result.status, result.err)
        assertTrue(result.err.contains("LLM explanations incomplete: 1 finding(s) not explained (HTTP 503 from provider)"), result.err)
        assertFalse(result.err.contains("unusual for an API key"), result.err)
    }

    @Test
    fun `an Ollama run says once that the output-token limit is not enforced, also in a dry run`() {
        val configs = ArrayList<LlmExplanationConfig>()
        val enricher = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList()) }
        val environment = CliEnvironment(explanationEnricherFactory = { configs += it; enricher }, env = env(null))

        val ollama = cli(checkArgs("--llm-provider", "ollama", "--llm-max-output-tokens", "900"), environment)
        assertEquals(EXIT_FINDINGS, ollama.status, ollama.err)
        val notes = ollama.err.lines().filter { it.contains("900 output tokens is not sent to ollama") }
        assertEquals(1, notes.size, ollama.err)
        assertEquals(900, configs.single().maxOutputTokens)

        val dry = cli(checkArgs("--llm-provider", "ollama", "--explain-dry-run"), environment)
        assertEquals(EXIT_FINDINGS, dry.status, dry.err)
        assertTrue(dry.err.contains("LLM explain dry-run: would send up to 8 findings (provider=OLLAMA"), dry.err)
        assertTrue(dry.err.contains("max output tokens=${LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS}"), dry.err)
        assertEquals(1, dry.err.lines().count { it.contains("output tokens is not sent to ollama") }, dry.err)
        assertEquals(1, configs.size, "a dry run calls no LLM")

        val openAi = cli(checkArgs("--explain-dry-run"), environment)
        assertFalse(openAi.err.contains("is not sent"), openAi.err)
        assertTrue(openAi.err.contains("max output tokens=${LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS}"), openAi.err)
    }

    @Test
    fun `--ollama-base and --llm-model are validated, also in a dry run, and --ollama-base is flagged with other providers`() {
        val enricher = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList()) }
        val environment = CliEnvironment(explanationEnricherFactory = { enricher }, env = env(null))

        for (bad in listOf("localhost:11434", "ftp://host/x", "http://", "not a url", "   ")) {
            for (extra in listOf(emptyList(), listOf("--explain-dry-run"))) {
                val result = cli(checkArgs("--llm-provider", "ollama", "--ollama-base", bad) + extra, environment)
                assertEquals(EXIT_USAGE, result.status, "'$bad' $extra: ${result.err}")
                assertTrue(result.err.contains("--ollama-base must be an http or https URL"), result.err)
            }
        }
        for (extra in listOf(emptyList(), listOf("--explain-dry-run"))) {
            val blank = cli(checkArgs("--llm-model", "  ") + extra, environment)
            assertEquals(EXIT_USAGE, blank.status, blank.err)
            assertTrue(blank.err.contains("--llm-model must not be blank"), blank.err)
        }
        val ignored = cli(checkArgs("--explain-dry-run", "--ollama-base", "http://localhost:11434"), environment)
        assertTrue(ignored.err.contains("--ollama-base is ignored"), ignored.err)
        val used = cli(checkArgs("--explain-dry-run", "--llm-provider", "ollama", "--ollama-base", "http://localhost:11434"), environment)
        assertFalse(used.err.contains("--ollama-base is ignored"), used.err)
    }

    @Test
    fun `an output that is a dangling symbolic link is refused before any LLM call`() {
        val link = dir.resolve("report-link.json")
        val created =
            try {
                Files.createSymbolicLink(link, dir.resolve("missing-dir").resolve("report.json"))
                true
            } catch (_: IOException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
        assumeTrue(created, "this platform or account cannot create symbolic links")

        var explainerCalls = 0
        val enricher = QualityExplanationEnricher { report, _ -> explainerCalls++; ExplainedQualityReport(report, emptyList()) }
        val environment = CliEnvironment(explanationEnricherFactory = { enricher }, env = env("sk-test-0123456789"))
        val err = StringBuilder()
        val status = runOntoQa(listOf("check", ontology().toString(), "--catalog", "owl-quality", "--explain", "--output", link.toString()), environment) { err.appendLine(it) }
        assertEquals(EXIT_RUNTIME_ERROR, status, err.toString())
        assertTrue(err.contains("Cannot write --output") && err.contains("symbolic link"), err.toString())
        assertEquals(0, explainerCalls, "the LLM was called although the output cannot be written")
    }

    /** A file system where [link] is a dangling symbolic link and [foreign] belongs to another user. */
    private class FakeFileSystem(val link: Path? = null, val foreign: Path? = null) : OutputFileSystem() {
        override fun isSymbolicLink(path: Path): Boolean = path == link?.toAbsolutePath()?.normalize() || super.isSymbolicLink(path)

        override fun realPath(path: Path): Path =
            if (path == link?.toAbsolutePath()?.normalize()) throw java.nio.file.NoSuchFileException(path.toString()) else super.realPath(path)

        override fun replacementProblem(target: Path): String? =
            if (target == foreign?.toAbsolutePath()?.normalize()) {
                "the file is owned by another user (alice): replacing it would change its owner; choose another path"
            } else {
                super.replacementProblem(target)
            }
    }

    @Test
    fun `dangling links and files of another user are refused by every command before any work`() {
        val onto = ontology().toString()
        val link = dir.resolve("dangling.json")
        val foreign = dir.resolve("foreign.json").also { it.writeText("theirs") }
        val cases = mapOf(link to "symbolic link whose target does not exist", foreign to "owned by another user (alice)")
        for ((output, reason) in cases) {
            var explainerCalls = 0
            val enricher = QualityExplanationEnricher { report, _ -> explainerCalls++; ExplainedQualityReport(report, emptyList()) }
            val environment =
                CliEnvironment(
                    enricherFactory = { error("no model is loaded when the output cannot be written") },
                    explanationEnricherFactory = { enricher },
                    env = env("sk-test-0123456789"),
                    outputFileSystem = FakeFileSystem(link, foreign),
                )
            for (argv in listOf(
                listOf("check", onto, "--catalog", "owl-quality", "--explain", "--output", output.toString()),
                listOf("pipeline", onto, "--catalog", "owl-quality", "--explain", "--output", output.toString()),
                listOf("enrich", onto, "--output", output.toString()),
                listOf("metrics", onto, "--output", output.toString()),
            )) {
                val err = StringBuilder()
                val status = runOntoQa(argv, environment) { err.appendLine(it) }
                assertEquals(EXIT_RUNTIME_ERROR, status, "$argv: $err")
                assertTrue(err.contains("Cannot write --output") && err.contains(reason), "$argv: $err")
                assertEquals(1, err.trim().lines().size, "one clear line: $err")
            }
            assertEquals(0, explainerCalls, "the LLM was called although $output cannot be written")
        }
        assertEquals("theirs", Files.readString(foreign))
        assertFalse(Files.exists(link))
    }

    @Test
    fun `the output check of an existing file leaves nothing behind`() {
        val existing = dir.resolve("report.json").also { it.writeText("old") }
        assertEquals(null, outputProblem(existing))
        assertEquals(null, OutputFileSystem().replacementProblem(existing))
        assertEquals(listOf("report.json"), Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() })
        assertEquals("old", Files.readString(existing))
    }

    @Test
    fun `stdout and stderr get their charset independently`() {
        val cp850 = java.nio.charset.Charset.forName("IBM850")
        val utf8 = Charsets.UTF_8

        fun charsets(out: String?, err: String?, console: java.nio.charset.Charset?) = standardStreamCharsets(StandardStreamFacts(out, err, console))

        // onto-qa check > report.json : the report is UTF-8, the messages stay in the console charset.
        assertEquals(StandardStreamCharsets(utf8, cp850), charsets(null, "IBM850", null))
        // onto-qa check 2> log.txt : the other way round.
        assertEquals(StandardStreamCharsets(cp850, utf8), charsets("IBM850", null, cp850))
        // Both on the console, both redirected.
        assertEquals(StandardStreamCharsets(cp850, cp850), charsets("IBM850", "IBM850", cp850))
        assertEquals(StandardStreamCharsets(utf8, utf8), charsets(null, null, null))
        // A JVM without the per-stream properties: the console decides for both streams.
        assertEquals(StandardStreamCharsets(cp850, cp850), charsets(null, null, cp850))
        // JDK 22+: System.console() exists although stdout is redirected; the stream property decides.
        assertEquals(StandardStreamCharsets(utf8, cp850), charsets(null, "IBM850", cp850))
        // An encoding name this JVM does not know falls back to the console charset, then to UTF-8.
        assertEquals(StandardStreamCharsets(cp850, utf8), charsets("no-such-charset", null, cp850))
        assertEquals(StandardStreamCharsets(utf8, utf8), charsets("no-such-charset", null, null))
        // Whatever this JVM is attached to, the facts can be read and give a charset per stream.
        val here = standardStreamCharsets(StandardStreamFacts.ofThisJvm())
        assertTrue(here.out.name().isNotEmpty() && here.err.name().isNotEmpty())
    }
}
