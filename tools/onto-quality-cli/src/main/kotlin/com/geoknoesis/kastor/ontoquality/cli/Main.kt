package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.embed.EnrichmentVocabulary
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    OntoQualityApp().main(args)
}

private val logger = LoggerFactory.getLogger("onto-qa")

private class OntoQualityApp : CliktCommand(name = "onto-qa") {
    init {
        subcommands(CheckCommand(), EnrichCommand(), PipelineCommand())
    }

    override fun run() = Unit
}

private class EnrichCommand : CliktCommand(name = "enrich") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val modelOpt by
        option(
            "--model",
            help = "${OnnxEmbeddingModel.MODEL_ID_MINILM} (bundled) | ${OnnxEmbeddingModel.MODEL_ID_CUSTOM} (local ONNX + tokenizer)",
        ).default(OnnxEmbeddingModel.MODEL_ID_MINILM)
    private val thresholdOpt by option("--threshold").double().default(0.85)
    private val cacheDirOpt by option("--cache-dir", help = "Model cache root (MiniLM only; default: ~/.kastor/onto-quality/models)").path()
    private val onnxOpt by option("--onnx", help = "Path to .onnx (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val tokenizerOpt by
        option("--tokenizer", help = "Path to tokenizer.json (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val embeddingDimOpt by
        option("--embedding-dim", help = "Hidden size from the ONNX graph (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").int()
    private val maxTokensOpt by option("--max-tokens", help = "Max sequence length (default: 512)").int().default(512)
    private val modelDisplayNameOpt by
        option("--model-display-name", help = "Provenance id for the model (default: ONNX file stem)")
    private val tokenizerNoteOpt by
        option("--tokenizer-note", help = "Provenance id for tokenizer, e.g. HuggingFace model id (default: display name)")
    private val outputOpt by option("--output", help = "Output Turtle path").path()

    override fun run() {
        val graph = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")
        loadOnnxEmbeddingModel(
            modelOpt = modelOpt,
            cacheDirOpt = cacheDirOpt,
            onnxOpt = onnxOpt,
            tokenizerOpt = tokenizerOpt,
            embeddingDimOpt = embeddingDimOpt,
            maxTokensOpt = maxTokensOpt,
            displayNameOpt = modelDisplayNameOpt,
            tokenizerNoteOpt = tokenizerNoteOpt,
        ).use { model ->
            val enricher = SemanticEnricher(model = model, threshold = thresholdOpt)
            System.err.println("Embedding and building similarity index (threshold=$thresholdOpt)…")
            val enriched = enricher.enrich(graph)

            val out =
                outputOpt ?: run {
                    val name = ontologyArg.fileName.toString().removeSuffix(".ttl")
                    ontologyArg.parent?.resolve("$name.enriched.ttl")
                        ?: Path.of("$name.enriched.ttl")
                }
            val turtle = enriched.serialize(RdfFormat.TURTLE)
            out.toAbsolutePath().parent?.toFile()?.mkdirs()
            out.toFile().writeText(turtle)
            System.err.println("Wrote enriched ontology to $out")
        }
    }
}

private class PipelineCommand : CliktCommand(name = "pipeline") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val modelOpt by
        option(
            "--model",
            help = "${OnnxEmbeddingModel.MODEL_ID_MINILM} (bundled) | ${OnnxEmbeddingModel.MODEL_ID_CUSTOM} (local ONNX + tokenizer)",
        ).default(OnnxEmbeddingModel.MODEL_ID_MINILM)
    private val thresholdOpt by option("--threshold").double().default(0.85)
    private val cacheDirOpt by option("--cache-dir", help = "Model cache root (MiniLM only; default: ~/.kastor/onto-quality/models)").path()
    private val onnxOpt by option("--onnx", help = "Path to .onnx (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val tokenizerOpt by
        option("--tokenizer", help = "Path to tokenizer.json (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val embeddingDimOpt by
        option("--embedding-dim", help = "Hidden size from the ONNX graph (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").int()
    private val maxTokensOpt by option("--max-tokens", help = "Max sequence length (default: 512)").int().default(512)
    private val modelDisplayNameOpt by
        option("--model-display-name", help = "Provenance id for the model (default: ONNX file stem)")
    private val tokenizerNoteOpt by
        option("--tokenizer-note", help = "Provenance id for tokenizer, e.g. HuggingFace model id (default: display name)")
    private val catalogOpt by
        option("--catalog", help = "owl-quality | skos-validation | data-quality | embedding-quality | all")
            .default("all")
    private val formatOpt by
        option("--format", help = "text | markdown | json | turtle").default("text")
    private val severityOpt by
        option("--severity", help = "violation | warning | info").default("violation")
    private val outputOpt by
        option("--output", help = "Write output to this file instead of stdout")
            .path()
    private val keepIntermediate by
        option("--keep-intermediate", help = "Keep the temporary enriched Turtle file")
            .flag(default = false)

    override fun run() {
        loadOnnxEmbeddingModel(
            modelOpt = modelOpt,
            cacheDirOpt = cacheDirOpt,
            onnxOpt = onnxOpt,
            tokenizerOpt = tokenizerOpt,
            embeddingDimOpt = embeddingDimOpt,
            maxTokensOpt = maxTokensOpt,
            displayNameOpt = modelDisplayNameOpt,
            tokenizerNoteOpt = tokenizerNoteOpt,
        ).use { model ->
            val enricher = SemanticEnricher(model = model, threshold = thresholdOpt)
            System.err.println("Pipeline: enriching…")
            val ontology = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")
            val enriched = enricher.enrich(ontology)

            val tmp = Files.createTempFile("onto-qa-", ".enriched.ttl")
            try {
                Files.writeString(tmp, enriched.serialize(RdfFormat.TURTLE))
                if (keepIntermediate) {
                    System.err.println("Kept intermediate enriched graph at $tmp")
                }

                val validator = ShaclValidation.validator()
                val checker = buildChecker(catalogOpt, validator)
                if (catalogOpt.lowercase() in setOf("all", "embedding-quality")) {
                    val hasClose =
                        enriched.getTriplesSequence().any {
                            it.predicate == EnrichmentVocabulary.semanticallyCloseTo
                        }
                    if (!hasClose) {
                        logger.info(
                            "Embedding-quality catalog selected but no {} enrichment triples were materialized; semantic similarity shapes will not fire.",
                            EnrichmentVocabulary.semanticallyCloseTo,
                        )
                    }
                }

                val report = checker.check(enriched)
                emitReport(report, formatOpt, severityOpt, outputOpt)
                System.err.println(
                    "Pipeline complete (catalog=$catalogOpt). Bundled shape sets include embedding-quality when --catalog all.",
                )

                if (shouldFail(report, severityThreshold(severityOpt))) {
                    exitProcess(1)
                }
            } finally {
                if (!keepIntermediate) {
                    Files.deleteIfExists(tmp)
                }
            }
        }
    }
}

private class CheckCommand : CliktCommand(name = "check") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val catalogOpt by
        option(
            "--catalog",
            help = "owl-quality | skos-validation | data-quality | embedding-quality | all",
        ).default("all")
    private val formatOpt by
        option("--format", help = "text | markdown | json | turtle").default("text")
    private val severityOpt by
        option("--severity", help = "violation | warning | info").default("violation")
    private val outputOpt by
        option("--output", help = "Write output to this file instead of stdout")
            .path()

    override fun run() {
        val validator = ShaclValidation.validator()
        val checker = buildChecker(catalogOpt, validator)

        val graph = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")

        if (catalogOpt.lowercase() in setOf("all", "embedding-quality")) {
            val hasClose =
                graph.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
            if (!hasClose) {
                logger.info(
                    "Embedding-quality catalog selected but no {} triples found in the ontology; semantic similarity shapes will not produce findings.",
                    EnrichmentVocabulary.semanticallyCloseTo,
                )
            }
        }

        val report = checker.check(graph)
        emitReport(report, formatOpt, severityOpt, outputOpt)
        if (shouldFail(report, severityThreshold(severityOpt))) {
            exitProcess(1)
        }
    }
}

private fun loadOnnxEmbeddingModel(
    modelOpt: String,
    cacheDirOpt: Path?,
    onnxOpt: Path?,
    tokenizerOpt: Path?,
    embeddingDimOpt: Int?,
    maxTokensOpt: Int,
    displayNameOpt: String?,
    tokenizerNoteOpt: String?,
): OnnxEmbeddingModel =
    try {
        OnnxEmbeddingModel.fromCliOptions(
            modelId = modelOpt,
            cacheRoot = cacheDirOpt?.toAbsolutePath(),
            onnxPath = onnxOpt?.toAbsolutePath(),
            tokenizerPath = tokenizerOpt?.toAbsolutePath(),
            embeddingDim = embeddingDimOpt,
            maxTokens = maxTokensOpt,
            displayName = displayNameOpt,
            tokenizerNote = tokenizerNoteOpt,
        )
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "Invalid embedding options")
    }

private fun buildChecker(catalogOpt: String, validator: com.geoknoesis.kastor.rdf.shacl.ShaclValidator): QualityChecker =
    when (catalogOpt.lowercase()) {
        "all" -> QualityChecker.default(validator)
        "owl-quality" ->
            QualityChecker.builder(validator).addCatalog(BundledCatalogs.OWL_QUALITY).build()
        "skos-validation" ->
            QualityChecker.builder(validator).addCatalog(BundledCatalogs.SKOS_VALIDATION).build()
        "data-quality" ->
            QualityChecker.builder(validator).addCatalog(BundledCatalogs.DATA_QUALITY).build()
        "embedding-quality" ->
            QualityChecker.builder(validator).addCatalog(BundledCatalogs.EMBEDDING_QUALITY).build()
        else ->
            throw UsageError(
                "Unknown --catalog $catalogOpt (expected owl-quality|skos-validation|data-quality|embedding-quality|all)",
            )
    }

private fun emitReport(
    report: QualityReport,
    formatOpt: String,
    severityOpt: String,
    outputOpt: Path?,
) {
    val format =
        when (formatOpt.lowercase()) {
            "text" -> OutputFormat.TEXT
            "markdown" -> OutputFormat.MARKDOWN
            "json" -> OutputFormat.JSON
            "turtle" -> OutputFormat.TURTLE
            else -> throw UsageError("Unknown --format $formatOpt (expected text|markdown|json|turtle)")
        }

    val text =
        when (format) {
            OutputFormat.TEXT -> report.describeText()
            OutputFormat.MARKDOWN -> report.describeMarkdown()
            OutputFormat.JSON -> findingsToJson(report)
            OutputFormat.TURTLE ->
                JenaBridge.toString(report.underlying.toShaclValidationReportRdf(), "TURTLE")
        }

    val sink = outputOpt
    if (sink != null) {
        sink.toFile().writeText(text)
    } else {
        println(text)
    }
}

private fun severityThreshold(severityOpt: String): SeverityThreshold =
    when (severityOpt.lowercase()) {
        "violation" -> SeverityThreshold.VIOLATION
        "warning" -> SeverityThreshold.WARNING
        "info" -> SeverityThreshold.INFO
        else -> throw UsageError("Unknown --severity $severityOpt (expected violation|warning|info)")
    }

private enum class OutputFormat {
    TEXT,
    MARKDOWN,
    JSON,
    TURTLE,
}

private enum class SeverityThreshold {
    INFO,
    WARNING,
    VIOLATION,
}

private fun ViolationSeverity.rank(): Int =
    when (this) {
        ViolationSeverity.TRACE,
        ViolationSeverity.DEBUG,
        ViolationSeverity.INFO,
        -> 0
        ViolationSeverity.WARNING -> 1
        ViolationSeverity.VIOLATION,
        ViolationSeverity.ERROR,
        -> 2
    }

private fun shouldFail(report: QualityReport, threshold: SeverityThreshold): Boolean =
    when (threshold) {
        SeverityThreshold.INFO -> false
        SeverityThreshold.WARNING ->
            report.underlying.violations.any { it.severity.rank() >= ViolationSeverity.WARNING.rank() } ||
                report.underlying.warnings.isNotEmpty()
        SeverityThreshold.VIOLATION ->
            report.underlying.violations.any {
                it.severity.rank() >= ViolationSeverity.VIOLATION.rank()
            }
    }

private fun findingsToJson(report: QualityReport): String {
    val rows =
        report.findings.map { f ->
            val pit =
                when (val p = f.pitfall) {
                    null -> "null"
                    PitfallReference.Convention -> "\"convention\""
                    is PitfallReference.Oops -> "\"OOPS:${escapeJson(p.number)}\""
                    is PitfallReference.Skos -> "\"SKOS:${escapeJson(p.number)}\""
                    is PitfallReference.OntoQuality -> "\"OntoQuality:${escapeJson(p.number)}\""
                }
            """
            {
              "severity": "${f.violation.severity.name}",
              "message": "${escapeJson(f.violation.message)}",
              "shapeUri": ${f.violation.shapeUri?.let { "\"${escapeJson(it)}\"" } ?: "null"},
              "category": "${f.category.name}",
              "pitfall": $pit,
              "tier": "${f.tier.name}",
              "focusNode": "${escapeJson(focusNodeToString(f.violation.focusNode))}"
            }
            """.trimIndent()
        }
    return "[${rows.joinToString(",\n")}]"
}

private fun focusNodeToString(term: RdfTerm): String =
    when (term) {
        is Iri -> term.value
        is BlankNode -> "_:${term.id}"
        is Literal -> term.lexical
        else -> term.toString()
    }

private fun escapeJson(s: String): String =
    buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
