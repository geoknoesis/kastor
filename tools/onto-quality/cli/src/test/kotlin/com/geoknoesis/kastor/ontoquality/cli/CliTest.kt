package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchBudgetExceededException
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.FindingExplanation
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    @TempDir lateinit var dir: Path

    private val ontologyTtl =
        """
        @prefix : <http://example.org/cli#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :Animal a owl:Class .
        :Dog a owl:Class ; rdfs:subClassOf :Animal .
        :owns a owl:ObjectProperty .
        """.trimIndent()

    /** No triples at all: no SHACL focus nodes, hence no findings in any catalog. */
    private val emptyTtl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"

    private fun ontology(name: String = "onto.ttl", content: String = ontologyTtl): Path =
        dir.resolve(name).also { it.writeText(content) }

    /** Fake enrichment step: records lifecycle, returns the graph unchanged (no ONNX model). */
    private class FakeEnricherFactory(val failure: Throwable? = null, val openFailure: Throwable? = null) : PipelineEnricherFactory {
        var opened = 0
        var closed = 0

        override fun open(options: EmbeddingCliOptions): PipelineEnricher {
            openFailure?.let { throw it }
            opened++
            return object : PipelineEnricher {
                override fun enrich(ontology: RdfGraph): RdfGraph {
                    failure?.let { throw it }
                    return ontology
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private class Run(val status: Int, val err: String)

    private fun run(argv: List<String>, environment: CliEnvironment = CliEnvironment()): Run {
        val err = StringBuilder()
        val status = runOntoQa(argv, environment) { err.appendLine(it) }
        return Run(status, err.toString())
    }

    private fun tempIntermediates(): Set<Path> =
        Path.of(System.getProperty("java.io.tmpdir")).listDirectoryEntries("onto-qa-*.enriched.ttl").toSet()

    private fun findingSeverities(json: String): List<String> =
        Json.parseToJsonElement(json).jsonObject.getValue("findings").jsonArray.map {
            it.jsonObject.getValue("severity").jsonPrimitive.content
        }

    // ---- P1: relative IRIs ------------------------------------------------------------------------------------

    @Test
    fun `Turtle with relative IRIs is parsed against the file URI`() {
        val file =
            ontology(
                "relative.ttl",
                """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                <> a owl:Ontology ; rdfs:label "Relative" .
                <#Foo> a owl:Class .
                <#Bar> a owl:Class ; rdfs:subClassOf <#Foo> .
                """.trimIndent(),
            )
        val base = file.toAbsolutePath().normalize().toUri().toString()
        val triples = parseOntology(file, RdfFormat.TURTLE).getTriples()
        assertTrue(RdfTriple(Iri(base), RDF.type, OWL.Ontology) in triples, triples.toString())
        assertTrue(RdfTriple(Iri("$base#Bar"), RDFS.subClassOf, Iri("$base#Foo")) in triples, triples.toString())

        val out = dir.resolve("relative-metrics.json")
        val result = run(listOf("metrics", file.toString(), "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_OK, result.status, result.err)
        assertTrue(Regex("\"totalNamedClasses\"\\s*:\\s*2").containsMatchIn(out.readText()), out.readText())
    }

    @Test
    fun `RDF-XML with relative about attributes is parsed against the file URI`() {
        val file =
            ontology(
                "relative.owl",
                """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:owl="http://www.w3.org/2002/07/owl#"
                         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Ontology rdf:about=""/>
                  <owl:Class rdf:about="#Foo"/>
                  <owl:Class rdf:about="#Bar">
                    <rdfs:subClassOf rdf:resource="#Foo"/>
                  </owl:Class>
                </rdf:RDF>
                """.trimIndent(),
            )
        val base = file.toAbsolutePath().normalize().toUri().toString()
        val triples = parseOntology(file, RdfFormat.RDF_XML).getTriples()
        assertTrue(RdfTriple(Iri(base), RDF.type, OWL.Ontology) in triples, triples.toString())
        assertTrue(RdfTriple(Iri("$base#Bar"), RDFS.subClassOf, Iri("$base#Foo")) in triples, triples.toString())

        val result = run(listOf("check", file.toString(), "--catalog", "rdf12-quality", "--severity", "violation", "--output", dir.resolve("x.txt").toString()))
        assertTrue(result.status == EXIT_OK || result.status == EXIT_FINDINGS, "parse must succeed: ${result.status} ${result.err}")
    }

    // ---- P2: exit codes ---------------------------------------------------------------------------------------

    @Test
    fun `exit 0 when no findings reach the threshold`() {
        val out = dir.resolve("empty.json")
        val result = run(listOf("check", ontology("empty.ttl", emptyTtl).toString(), "--catalog", "all", "--severity", "info", "--format", "json", "--output", out.toString()))
        assertEquals(emptyList(), findingSeverities(out.readText()))
        assertEquals(EXIT_OK, result.status, result.err)
    }

    @Test
    fun `exit 1 when findings reach --severity, including info`() {
        val onto = ontology()
        val out = dir.resolve("report.json")
        val strict = run(listOf("check", onto.toString(), "--catalog", "owl-quality", "--format", "json", "--output", out.toString()))
        val severities = findingSeverities(out.readText())
        assertTrue(severities.isNotEmpty(), "fixture must produce findings")
        val blocking = severities.any { it == "VIOLATION" || it == "ERROR" }
        assertEquals(if (blocking) EXIT_FINDINGS else EXIT_OK, strict.status, strict.err)

        // --severity info fails on any finding at or above INFO.
        val lenient = run(listOf("check", onto.toString(), "--catalog", "owl-quality", "--severity", "info", "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_FINDINGS, lenient.status, lenient.err)
    }

    @Test
    fun `exit 2 when the input cannot be parsed`() {
        val turtleInOwl = ontology("turtle.owl")
        val result = run(listOf("metrics", turtleInOwl.toString()))
        assertEquals(EXIT_INPUT_ERROR, result.status)
        assertTrue(result.err.contains("Failed to parse"), result.err)
        assertEquals(EXIT_INPUT_ERROR, ontoQualityApp().test(listOf("metrics", turtleInOwl.toString())).statusCode)
    }

    @Test
    fun `--fail-on-explain-error turns LLM failures into exit status 3`() {
        val failing = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList(), listOf(ExplanationFailure(emptyList(), "down"))) }
        val throwing = QualityExplanationEnricher { _, _ -> throw IllegalStateException("no key") }
        val env = { name: String -> if (name == LLM_EXPLAIN_ENV) "true" else null }
        val argv = listOf("check", ontology("empty.ttl", emptyTtl).toString(), "--catalog", "all", "--severity", "info", "--explain", "--output", dir.resolve("o.txt").toString())

        for (enricher in listOf(failing, throwing)) {
            val lenient = ontoQualityApp(CliEnvironment(explanationEnricherFactory = { enricher }, env = env)).test(argv)
            assertEquals(EXIT_OK, lenient.statusCode, lenient.stderr)
            assertTrue(lenient.stderr.contains("LLM explanations"), lenient.stderr)
            val strict = run(argv + "--fail-on-explain-error", CliEnvironment(explanationEnricherFactory = { enricher }, env = env))
            assertEquals(EXIT_EXPLAIN_ERROR, strict.status, strict.err)
        }
    }

    @Test
    fun `exit 4 for usage and configuration errors, raised before any model is loaded`() {
        val onto = ontology().toString()
        val cases =
            listOf(
                listOf("pipeline", onto, "--format", "bogus"),
                listOf("pipeline", onto, "--severity", "fatal"),
                listOf("pipeline", onto, "--threshold", "2.5"),
                listOf("pipeline", onto, "--threshold", "abc"),
                listOf("pipeline", onto, "--explain-batch", "0"),
                listOf("pipeline", onto, "--explain-max", "${MAX_EXPLAIN_FINDINGS + 1}"),
                listOf("pipeline", onto, "--similarity-max-work", "0"),
                listOf("pipeline", onto, "--llm-max-duration", "0"),
                listOf("check", onto, "--reasoner", "magic"),
                listOf("check", onto, "--no-such-option"),
                listOf("check"),
                listOf("check", dir.resolve("missing.ttl").toString()),
                listOf("enrich", onto, "--max-tokens", "0"),
                listOf("enrich", onto, "--model", "custom"),
                listOf("enrich", onto, "--max-tokens", "600"),
                listOf("frobnicate"),
                listOf("metrics", onto, "--format", "json", "--include", "owl"),
                listOf("metrics", onto, "--format", "turtle", "--include", "skos"),
            )
        for (argv in cases) {
            val factory = FakeEnricherFactory()
            val result = run(argv, CliEnvironment(enricherFactory = factory))
            assertEquals(EXIT_USAGE, result.status, "expected usage error for $argv: ${result.err}")
            assertEquals(0, factory.opened, "model must not be loaded for $argv")
            assertFalse(result.err.contains("Exception"), "no stack trace for $argv: ${result.err}")
        }
    }

    @Test
    fun `exit 5 for runtime failures with a concise message and no usage help`() {
        val onto = ontology().toString()
        val cases =
            listOf(
                FakeEnricherFactory(openFailure = java.io.IOException("HTTP 503 downloading model.onnx")) to "HTTP 503 downloading model.onnx",
                FakeEnricherFactory(openFailure = IllegalArgumentException("SHA-256 mismatch downloading model.onnx")) to "SHA-256 mismatch downloading model.onnx",
                FakeEnricherFactory(failure = IllegalStateException("enrichment exploded")) to "enrichment exploded",
            )
        for ((factory, message) in cases) {
            val before = tempIntermediates()
            val result = run(listOf("pipeline", onto, "--catalog", "owl-quality"), CliEnvironment(enricherFactory = factory))
            assertEquals(EXIT_RUNTIME_ERROR, result.status, result.err)
            assertTrue(result.err.contains(message), result.err)
            assertFalse(result.err.contains("Usage:"), result.err)
            assertFalse(result.err.contains("\tat "), "no stack trace without --debug: ${result.err}")
            assertEquals(factory.opened, factory.closed)
            assertEquals(before, tempIntermediates())
        }

        // Clikt's test harness sees the same status for runtime failures.
        val harness = ontoQualityApp(CliEnvironment(enricherFactory = FakeEnricherFactory(failure = IllegalStateException("boom")))).test(listOf("pipeline", onto))
        assertEquals(EXIT_RUNTIME_ERROR, harness.statusCode)

        // --debug adds the stack trace.
        val debug = run(listOf("--debug", "pipeline", onto), CliEnvironment(enricherFactory = FakeEnricherFactory(failure = IllegalStateException("boom"))))
        assertEquals(EXIT_RUNTIME_ERROR, debug.status)
        assertTrue(debug.err.contains("\tat "), debug.err)

        // Output that cannot be written is a runtime (IO) failure, after the model has been closed.
        val writeFailure = FakeEnricherFactory()
        val unwritable = Files.createDirectory(dir.resolve("is-a-directory"))
        val io = run(listOf("pipeline", onto, "--catalog", "owl-quality", "--output", unwritable.toString()), CliEnvironment(enricherFactory = writeFailure))
        assertEquals(EXIT_RUNTIME_ERROR, io.status, io.err)
        assertEquals(1, writeFailure.closed)
    }

    @Test
    fun `similarity budget exhaustion is exit 5 with a hint about the similarity options`() {
        val factory = FakeEnricherFactory(failure = SimilaritySearchBudgetExceededException("Similarity distance-evaluation limit exceeded"))
        val result = run(listOf("enrich", ontology().toString()), CliEnvironment(enricherFactory = factory))
        assertEquals(EXIT_RUNTIME_ERROR, result.status, result.err)
        assertTrue(result.err.contains("Similarity distance-evaluation limit exceeded"), result.err)
        assertTrue(result.err.contains("--similarity-max-work") && result.err.contains("--similarity-timeout") && result.err.contains("--similarity-mode approximate"), result.err)
        assertFalse(result.err.contains("\tat "), result.err)
    }

    @Test
    fun `similarity options map to scaled limits with overrides`() {
        fun options(maxWork: Long? = null, timeout: Duration? = null, mode: SimilaritySearchMode = SimilaritySearchMode.Exact) =
            EmbeddingCliOptions(
                modelId = "all-MiniLM-L6-v2", cacheDir = null, onnx = null, tokenizer = null, embeddingDim = null, maxTokens = 512,
                displayName = null, tokenizerNote = null, threshold = 0.85,
                similarityMaxWork = maxWork, similarityTimeout = timeout, similarityMode = mode,
            )
        val scaled = options().similarityLimitsPolicy().limitsFor(20_000)
        assertEquals(200_290_000L, scaled.maxDistanceEvaluations)
        assertEquals(Duration.ofMillis(40_058), scaled.timeout)
        val overridden = options(maxWork = 10, timeout = Duration.ofSeconds(3)).similarityLimitsPolicy().limitsFor(20_000)
        assertEquals(10L, overridden.maxDistanceEvaluations)
        assertEquals(Duration.ofSeconds(3), overridden.timeout)

        val captured = mutableListOf<EmbeddingCliOptions>()
        val capture = PipelineEnricherFactory { opts -> captured += opts; FakeEnricherFactory().open(opts) }
        val result =
            run(
                listOf("enrich", ontology().toString(), "--similarity-max-work", "1234", "--similarity-timeout", "7", "--similarity-mode", "approximate", "--output", dir.resolve("e.ttl").toString()),
                CliEnvironment(enricherFactory = capture),
            )
        assertEquals(EXIT_OK, result.status, result.err)
        assertEquals(1234L, captured.single().similarityMaxWork)
        assertEquals(Duration.ofSeconds(7), captured.single().similarityTimeout)
        assertEquals(SimilaritySearchMode.ApproximateLsh(), captured.single().similarityMode)
    }

    @Test
    fun `--llm-max-duration bounds the whole explanation run`() {
        val configs = mutableListOf<LlmExplanationConfig>()
        val enricher = QualityExplanationEnricher { report, _ -> ExplainedQualityReport(report, emptyList()) }
        val env = { name: String -> if (name == LLM_EXPLAIN_ENV) "true" else null }
        val environment = CliEnvironment(explanationEnricherFactory = { configs += it; enricher }, env = env)
        val argv = listOf("check", ontology("empty.ttl", emptyTtl).toString(), "--catalog", "all", "--explain", "--output", dir.resolve("o.txt").toString())
        assertEquals(EXIT_OK, run(argv, environment).status)
        assertEquals(EXIT_OK, run(argv + listOf("--llm-max-duration", "90"), environment).status)
        assertEquals(listOf(Duration.ofMinutes(10), Duration.ofSeconds(90)), configs.map { it.maxTotalDuration })
    }

    // ---- other CLI behaviour ----------------------------------------------------------------------------------

    @Test
    fun `owl-rl and owl-micro reasoners are both accepted without warnings`() {
        val onto = ontology().toString()
        fun expectedStatus(json: Path): Int =
            if (findingSeverities(json.readText()).any { it == "VIOLATION" || it == "ERROR" }) EXIT_FINDINGS else EXIT_OK

        // OWL RL materialisation adds axiomatic triples, so the status is derived from the reported findings.
        val rlOut = dir.resolve("rl.json")
        val rl = ontoQualityApp().test(listOf("check", onto, "--catalog", "owl-quality", "--reasoner", "owl-rl", "--format", "json", "--output", rlOut.toString()))
        assertEquals(expectedStatus(rlOut), rl.statusCode, rl.stderr)
        assertFalse(rl.stderr.contains("deprecated"), rl.stderr)

        val microOut = dir.resolve("micro.json")
        val micro = ontoQualityApp().test(listOf("check", onto, "--catalog", "owl-quality", "--reasoner", "owl-micro", "--format", "json", "--output", microOut.toString()))
        assertEquals(expectedStatus(microOut), micro.statusCode, micro.stderr)
        // owl-micro is a first-class profile (Jena OWL Micro), no longer a deprecated alias of owl-rl.
        assertFalse(micro.stderr.contains("deprecated"), micro.stderr)
    }

    @Test
    fun `JSON report escapes every control character`() {
        val nasty = "bell\u0007 formfeed\u000C nul\u0000 quote\" backslash\\ newline\n tab\t unit\u001F"
        val report = report(nasty)
        val ref = FindingRef.from(report.findings.single())
        val explained =
            ExplainedQualityReport(
                report,
                listOf(FindingExplanation(ref, nasty, nasty, listOf(nasty), nasty, "m\u0001", "p", "r")),
                listOf(ExplanationFailure(listOf(ref), nasty)),
            )
        val json = findingsToJson(report, explained)
        assertFalse(json.any { it.code < 0x20 && it != '\n' && it != ' ' }, "raw control characters in JSON output")
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(nasty, root.getValue("findings").jsonArray.single().jsonObject.getValue("message").jsonPrimitive.content)
        val expl = root.getValue("llmExplanations").jsonArray.single().jsonObject
        assertEquals(nasty, expl.getValue("suggestedActions").jsonArray.single().jsonPrimitive.content)
        assertEquals(nasty, root.getValue("llmExplanationFailures").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `pipeline closes the enricher, writes no intermediate file and reports status after cleanup`() {
        val factory = FakeEnricherFactory()
        val before = tempIntermediates()
        val out = dir.resolve("pipeline.json")
        val result =
            ontoQualityApp(CliEnvironment(enricherFactory = factory)).test(
                listOf("pipeline", ontology().toString(), "--catalog", "owl-quality", "--format", "json", "--output", out.toString(), "--severity", "warning"),
            )
        assertEquals(1, factory.opened)
        assertEquals(1, factory.closed)
        assertEquals(before, tempIntermediates())
        val severities = findingSeverities(out.readText())
        val expected = if (severities.any { it != "INFO" && it != "DEBUG" && it != "TRACE" }) EXIT_FINDINGS else EXIT_OK
        assertEquals(expected, result.statusCode, result.stderr)
    }

    @Test
    fun `keep-intermediate writes the enriched graph`() {
        val factory = FakeEnricherFactory()
        val before = tempIntermediates()
        val result =
            ontoQualityApp(CliEnvironment(enricherFactory = factory)).test(
                listOf("pipeline", ontology("empty.ttl", emptyTtl).toString(), "--catalog", "all", "--severity", "info", "--keep-intermediate", "--output", dir.resolve("r.txt").toString()),
            )
        assertEquals(EXIT_OK, result.statusCode, result.stderr)
        val created = tempIntermediates() - before
        try {
            assertEquals(1, created.size, result.stderr)
            assertTrue(result.stderr.contains(created.single().toString()))
        } finally {
            created.forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `input format is detected from the extension and can be overridden`() {
        val rdfXml =
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
              <owl:Class rdf:about="http://example.org/x#A"/>
              <owl:Class rdf:about="http://example.org/x#B">
                <rdfs:subClassOf rdf:resource="http://example.org/x#A"/>
              </owl:Class>
            </rdf:RDF>
            """.trimIndent()
        val owl = ontology("onto.owl", rdfXml)
        assertEquals(RdfFormat.RDF_XML, resolveInputFormat(owl, null))
        assertEquals(RdfFormat.N_TRIPLES, resolveInputFormat(Path.of("a.nt"), null))
        assertEquals(RdfFormat.JSON_LD, resolveInputFormat(Path.of("a.jsonld"), null))
        assertEquals(RdfFormat.TURTLE, resolveInputFormat(Path.of("a.ttl"), null))
        assertEquals(RdfFormat.TURTLE, resolveInputFormat(Path.of("a.owl"), "turtle"))

        val out = dir.resolve("metrics.json")
        val result = ontoQualityApp().test(listOf("metrics", owl.toString(), "--format", "json", "--output", out.toString()))
        assertEquals(EXIT_OK, result.statusCode, result.stderr)
        assertTrue(Regex("\"totalNamedClasses\"\\s*:\\s*2").containsMatchIn(out.readText()), out.readText())

        val turtleInOwl = ontology("turtle.owl")
        val overridden = ontoQualityApp().test(listOf("metrics", turtleInOwl.toString(), "--input-format", "turtle", "--output", dir.resolve("m.txt").toString()))
        assertEquals(EXIT_OK, overridden.statusCode, overridden.stderr)
        assertEquals(EXIT_USAGE, run(listOf("metrics", turtleInOwl.toString(), "--input-format", "yaml")).status)
    }

    @Test
    fun `metrics include slices render for text and markdown`() {
        val onto = ontology().toString()
        for (format in listOf("text", "markdown")) {
            for (include in listOf("graph", "owl", "skos")) {
                val result = ontoQualityApp().test(listOf("metrics", onto, "--format", format, "--include", include))
                assertEquals(EXIT_OK, result.statusCode, "$format/$include: ${result.output}")
                assertTrue(result.stdout.isNotBlank())
            }
        }
        val out = dir.resolve("never.json")
        assertEquals(EXIT_USAGE, run(listOf("metrics", onto, "--format", "json", "--include", "owl", "--output", out.toString())).status)
        assertFalse(out.exists(), "usage errors are raised before any output is written")
        assertEquals(EXIT_OK, run(listOf("metrics", onto, "--format", "json", "--include", "all", "--output", out.toString())).status)
    }

    private fun report(message: String): QualityReport {
        val violation =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = Iri("http://example.org/A"),
                message = message,
            )
        val raw =
            ValidationReport(
                isValid = false,
                violations = listOf(violation),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = 1,
                        validatedResources = 1,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = emptyMap(),
                        violationsByType = emptyMap(),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 1,
                validatedConstraints = 1,
            )
        return QualityReport.from(raw, emptyList())
    }
}
