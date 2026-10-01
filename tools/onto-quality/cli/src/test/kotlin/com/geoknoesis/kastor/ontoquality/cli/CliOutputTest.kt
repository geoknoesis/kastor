package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Output files, exit statuses, `--debug` traces and LLM failure reporting of `onto-qa`. */
class CliOutputTest {
    @TempDir lateinit var dir: Path

    private val escape = Char(0x1B)
    private val bidiOverride = Char(0x202E)
    private val hostile = "down$escape[2J${bidiOverride}evil"

    private val ontologyTtl =
        """
        @prefix : <http://example.org/cli#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :Animal a owl:Class .
        :Dog a owl:Class ; rdfs:subClassOf :Animal .
        """.trimIndent()

    /** OOPS P26: an owl:SymmetricProperty with owl:inverseOf violates a `sh:Violation` shape of owl-quality. */
    private val violationTtl =
        """
        @prefix : <http://example.org/sym#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        :knows a owl:ObjectProperty , owl:SymmetricProperty ; owl:inverseOf :knownBy .
        :knownBy a owl:ObjectProperty .
        """.trimIndent()

    private fun ontology(name: String = "onto.ttl", content: String = ontologyTtl): Path = dir.resolve(name).also { it.writeText(content) }

    private class FakeEnricherFactory(val failure: Throwable? = null, val openFailure: Throwable? = null) : PipelineEnricherFactory {
        var opened = 0

        override fun open(options: EmbeddingCliOptions): PipelineEnricher {
            openFailure?.let { throw it }
            opened++
            return object : PipelineEnricher {
                override fun enrich(ontology: RdfGraph): RdfGraph {
                    failure?.let { throw it }
                    return ontology
                }

                override fun close() = Unit
            }
        }
    }

    private val apiKey = "sk-test-SECRET-0123456789"

    private val llmEnv = { name: String ->
        when (name) {
            LLM_EXPLAIN_ENV -> "true"
            LlmExplanationConfig.OPENAI_API_KEY -> apiKey
            else -> null
        }
    }

    private class Run(val status: Int, val err: String)

    private fun run(argv: List<String>, environment: CliEnvironment = CliEnvironment()): Run {
        val err = StringBuilder()
        val status = runOntoQa(argv, environment) { err.appendLine(it) }
        return Run(status, err.toString())
    }

    private fun assertNoRawControls(text: String) {
        val bad = text.filter { (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
        assertEquals("", bad, "raw control or bidi characters in: $text")
    }

    /** Stack frames, as printed by Clikt's test terminal (which renders the leading tab as spaces). */
    private fun hasStackTrace(text: String): Boolean = Regex("\\n\\s+at [\\w.$]+\\(").containsMatchIn(text)

    private fun leftovers(): List<String> = dir.listDirectoryEntries().map { it.name }.filter { it.contains(".tmp") }

    // ---- finding 6: --format turtle --explain ------------------------------------------------------------------

    @Test
    fun `--explain with --format turtle is a usage error raised before any LLM call`() {
        var explainerCalls = 0
        val enricher = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList()) }
        for (command in listOf("check", "pipeline")) {
            for (extra in listOf(emptyList(), listOf("--explain-dry-run"))) {
                val factory = FakeEnricherFactory()
                val environment = CliEnvironment(enricherFactory = factory, explanationEnricherFactory = { explainerCalls++; enricher }, env = llmEnv)
                val out = dir.resolve("never.ttl")
                val result = run(listOf(command, ontology().toString(), "--catalog", "owl-quality", "--format", "turtle", "--explain", "--output", out.toString()) + extra, environment)
                assertEquals(EXIT_USAGE, result.status, result.err)
                assertTrue(result.err.contains("--explain cannot be combined with --format turtle"), result.err)
                assertEquals(0, factory.opened)
                assertFalse(out.exists())
            }
        }
        assertEquals(0, explainerCalls)
        // Turtle without --explain is still supported.
        val out = dir.resolve("report.ttl")
        assertEquals(EXIT_FINDINGS, run(listOf("check", ontology("violation.ttl", violationTtl).toString(), "--catalog", "owl-quality", "--format", "turtle", "--output", out.toString())).status)
        assertTrue(out.readText().contains("ValidationReport"), out.readText())
    }

    // ---- finding 7: output files --------------------------------------------------------------------------------

    @Test
    fun `--output creates parent directories for every command and replaces existing files`() {
        val onto = ontology().toString()
        val metrics = dir.resolve("new/nested/metrics.json")
        assertEquals(EXIT_OK, run(listOf("metrics", onto, "--format", "json", "--output", metrics.toString())).status)
        assertTrue(metrics.readText().contains("totalNamedClasses"))

        val report = dir.resolve("reports/deep/report.json")
        assertEquals(EXIT_OK, run(listOf("check", onto, "--catalog", "rdf12-quality", "--format", "json", "--output", report.toString())).status)
        assertTrue(report.readText().contains("\"findings\""))

        val enriched = dir.resolve("out/dir/enriched.ttl")
        val enrich = run(listOf("enrich", onto, "--output", enriched.toString()), CliEnvironment(enricherFactory = FakeEnricherFactory()))
        assertEquals(EXIT_OK, enrich.status, enrich.err)
        assertTrue(enriched.readText().contains("Dog"))

        // An existing, longer file is replaced as a whole.
        report.writeText("x".repeat(100_000))
        assertEquals(EXIT_OK, run(listOf("check", onto, "--catalog", "rdf12-quality", "--format", "json", "--output", report.toString())).status)
        assertTrue(report.readText().trimEnd().endsWith("}") && !report.readText().contains("xxxx"))
        for (folder in listOf(dir, metrics.parent, report.parent, enriched.parent)) {
            assertEquals(emptyList(), folder.listDirectoryEntries().map { it.name }.filter { it.contains(".tmp") }, "temporary files left in $folder")
        }
    }

    @Test
    fun `an output that is the input is refused unless --overwrite-input is given`() {
        val onto = ontology()
        val before = onto.readText()
        for (output in listOf(onto.toString(), dir.resolve("sub/../onto.ttl").toString())) {
            val factory = FakeEnricherFactory()
            for (argv in listOf(
                listOf("metrics", onto.toString(), "--output", output),
                listOf("check", onto.toString(), "--catalog", "owl-quality", "--output", output),
                listOf("pipeline", onto.toString(), "--catalog", "owl-quality", "--output", output),
                listOf("enrich", onto.toString(), "--output", output),
            )) {
                val result = run(argv, CliEnvironment(enricherFactory = factory))
                assertEquals(EXIT_USAGE, result.status, "$argv: ${result.err}")
                assertTrue(result.err.contains("--output is the input file") && result.err.contains("--overwrite-input"), result.err)
                assertEquals(before, onto.readText())
            }
            assertEquals(0, factory.opened, "nothing may be loaded before the output is validated")
        }
        val forced = run(listOf("enrich", onto.toString(), "--output", onto.toString(), "--overwrite-input"), CliEnvironment(enricherFactory = FakeEnricherFactory()))
        assertEquals(EXIT_OK, forced.status, forced.err)
        assertTrue(onto.readText().contains("Dog"))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a failed write leaves no partial or temporary file`() {
        val target = Files.createDirectory(dir.resolve("is-a-directory"))
        Files.writeString(target.resolve("keep.txt"), "keep")
        val result = run(listOf("metrics", ontology().toString(), "--output", target.toString()))
        assertEquals(EXIT_RUNTIME_ERROR, result.status, result.err)
        assertTrue(target.isDirectory() && target.resolve("keep.txt").exists())
        // An empty directory must not be replaced by the report either.
        val empty = Files.createDirectory(dir.resolve("empty-directory"))
        assertEquals(EXIT_RUNTIME_ERROR, run(listOf("metrics", ontology().toString(), "--output", empty.toString())).status)
        assertTrue(empty.isDirectory())
        assertEquals(emptyList(), leftovers())

        val file = dir.resolve("atomic.txt")
        writeFileAtomically(file, "first")
        writeFileAtomically(file, "second")
        assertEquals("second", file.readText())
        assertEquals(emptyList(), leftovers())
    }

    // ---- finding 5: exit statuses -------------------------------------------------------------------------------

    @Test
    fun `I-O errors and a missing RDF provider are runtime errors, parse errors are input errors`() {
        val missing = assertFailsWith<CliktError> { parseOntology(dir.resolve("missing.ttl"), RdfFormat.TURTLE) }
        assertEquals(EXIT_RUNTIME_ERROR, missing.statusCode, missing.message)
        assertTrue(missing.message!!.startsWith("Failed to read "), missing.message)

        val noProvider = assertFailsWith<CliktError> { parseOntology(ontology(), RdfFormat.TURTLE, providers = emptyList()) }
        assertEquals(EXIT_RUNTIME_ERROR, noProvider.statusCode, noProvider.message)
        assertTrue(noProvider.message!!.contains("no RDF provider can parse"), noProvider.message)

        val broken = ontology("broken.ttl", "<http://e/s> <http://e/p> .")
        val parse = assertFailsWith<CliktError> { parseOntology(broken, RdfFormat.TURTLE) }
        assertEquals(EXIT_INPUT_ERROR, parse.statusCode, parse.message)
        assertEquals(EXIT_INPUT_ERROR, run(listOf("metrics", broken.toString())).status)
    }

    @Test
    fun `help documents the exit statuses of both command-line tools`() {
        for (argv in listOf(listOf("--help"), listOf("check", "--help"), listOf("metrics", "--help"))) {
            val help = ontoQualityApp().test(argv).output.replace(Regex("\\s+"), " ")
            assertTrue(help.contains("2 the ontology could not be parsed"), help)
            assertTrue(help.contains("5 runtime error (model download or loading, similarity or LLM budget, I/O"), help)
            assertTrue(help.contains("kastor-rdf uses a different convention: 0 success; 1 usage or input error"), help)
        }
    }

    // ---- finding 4: --debug traces ------------------------------------------------------------------------------

    @Test
    fun `every --debug stack trace is sanitised`() {
        val onto = ontology().toString()
        val runtime = run(listOf("--debug", "pipeline", onto, "--catalog", "owl-quality"), CliEnvironment(enricherFactory = FakeEnricherFactory(failure = IllegalStateException(hostile))))
        assertEquals(EXIT_RUNTIME_ERROR, runtime.status, runtime.err)
        assertTrue(runtime.err.contains("\tat ") && runtime.err.contains("java.lang.IllegalStateException: down\\u001B[2J\\u202Eevil"), runtime.err)
        assertNoRawControls(runtime.err)

        val internal = run(listOf("--debug", "pipeline", onto, "--catalog", "owl-quality"), CliEnvironment(enricherFactory = FakeEnricherFactory(openFailure = StackOverflowError(hostile))))
        assertEquals(EXIT_RUNTIME_ERROR, internal.status, internal.err)
        assertTrue(internal.err.contains("onto-qa: internal error: StackOverflowError") && internal.err.contains("\tat "), internal.err)
        assertNoRawControls(internal.err)
    }

    @Test
    fun `--debug prints the cause of a failed LLM request made by the production enricher`() {
        val body = "{\"error\":\"unauthorized $escape[2J\"}".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val optIn = { name: String -> if (name == LLM_EXPLAIN_ENV) "true" else null }
            val argv =
                listOf(
                    "check", ontology("violation.ttl", violationTtl).toString(), "--catalog", "owl-quality", "--explain", "--llm-provider", "ollama",
                    "--ollama-base", "http://127.0.0.1:${server.address.port}", "--llm-retries", "0", "--llm-timeout", "30", "--output", dir.resolve("o.txt").toString(),
                )
            // The default environment builds the production (Koog) enricher; only the endpoint is local.
            val quiet = ontoQualityApp(CliEnvironment(env = optIn)).test(argv)
            assertEquals(EXIT_FINDINGS, quiet.statusCode, quiet.stderr)
            assertTrue(quiet.stderr.contains("LLM explanations incomplete"), quiet.stderr)
            assertFalse(hasStackTrace(quiet.stderr), quiet.stderr)
            assertTrue(requests > 0, "the fake LLM endpoint was not called")

            val debug = ontoQualityApp(CliEnvironment(env = optIn)).test(argv + "--debug")
            assertEquals(EXIT_FINDINGS, debug.statusCode, debug.stderr)
            assertTrue(debug.stderr.contains("LLM explanation failure cause:") && hasStackTrace(debug.stderr), debug.stderr)
            assertTrue(debug.stderr.contains("Status code: 401"), debug.stderr)
            assertNoRawControls(debug.stderr)
            // Without --debug nothing but the one-line reason is printed, and the report still carries it.
            assertTrue(dir.resolve("o.txt").readText().contains("LLM explanation failures: 8 finding(s) not explained"), dir.resolve("o.txt").readText())
        } finally {
            server.stop(0)
        }
    }

    // ---- finding 11: API key ------------------------------------------------------------------------------------

    @Test
    fun `the API key reaches the LLM configuration and is redacted everywhere else`() {
        val configs = mutableListOf<LlmExplanationConfig>()
        val leaking = IllegalStateException("401 Incorrect API key provided: $apiKey")
        val failing =
            QualityExplanationEnricher { report, _ ->
                ExplainedQualityReport(report, emptyList(), listOf(ExplanationFailure(emptyList(), "HTTP 401 for key $apiKey", leaking)))
            }
        val throwing = QualityExplanationEnricher { _, _ -> throw leaking }
        val onto = ontology("violation.ttl", violationTtl).toString()

        for (enricher in listOf(failing, throwing)) {
            for (format in listOf("json", "text", "markdown")) {
                val environment = CliEnvironment(explanationEnricherFactory = { configs += it; enricher }, env = llmEnv)
                val out = dir.resolve("report.$format")
                val result = ontoQualityApp(environment).test(listOf("check", onto, "--catalog", "owl-quality", "--explain", "--debug", "--format", format, "--output", out.toString()))
                assertEquals(EXIT_FINDINGS, result.statusCode, result.stderr)
                assertTrue(hasStackTrace(result.stderr) && result.stderr.contains("Incorrect API key provided: ***"), result.stderr)
                assertFalse(result.output.contains(apiKey), "API key on stderr: ${result.stderr}")
                assertFalse(out.readText().contains(apiKey), "API key in the $format report")
                val stdout = ontoQualityApp(environment).test(listOf("check", onto, "--catalog", "owl-quality", "--explain", "--debug", "--format", format))
                assertFalse(stdout.output.contains(apiKey), "API key in the $format output")
            }
        }
        assertTrue(configs.isNotEmpty() && configs.all { it.apiKey == apiKey }, "the key is forwarded to the LLM configuration")
        assertFalse(configs.first().toString().contains(apiKey))
        val cli = LlmExplainCli(false, com.geoknoesis.kastor.ontoquality.llm.LlmProvider.OPENAI, null, com.geoknoesis.kastor.ontoquality.llm.ExplanationModelPreset.AUTO, null, 1, 1, com.geoknoesis.kastor.rdf.shacl.ViolationSeverity.WARNING, java.time.Duration.ofSeconds(1), 0, java.time.Duration.ofSeconds(1), apiKey = apiKey)
        assertFalse(cli.toString().contains(apiKey))
    }
}
