package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes [text] (UTF-8) to [output] through a temporary file in the same directory that is then moved over the
 * target, so a reader never sees a half-written file and a failed write leaves the previous content in place.
 * Parent directories are created; an existing file is replaced (keeping its POSIX permissions); a directory is not.
 */
internal fun writeFileAtomically(output: Path, text: String) {
    var target = output.toAbsolutePath().normalize()
    if (Files.isSymbolicLink(target)) {
        target =
            try {
                target.toRealPath()
            } catch (e: IOException) {
                throw IOException("$output is a symbolic link whose target does not exist or cannot be resolved (${e.javaClass.simpleName})", e)
            }
    }
    if (Files.isDirectory(target)) throw IOException("$output is a directory")
    val parent = target.parent ?: throw IOException("$output has no parent directory")
    Files.createDirectories(parent)
    val temp = parent.resolve(".${target.fileName}.${java.util.UUID.randomUUID()}.tmp")
    try {
        // CREATE_NEW with the default permissions of new files (a createTempFile would restrict them to the owner).
        Files.newBufferedWriter(temp, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(text) }
        if (Files.exists(target)) {
            try {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
            } catch (_: UnsupportedOperationException) {
                // Not a POSIX file system.
            } catch (e: IOException) {
                // Normally found by outputProblem before any work; a replacement with other permissions is not written.
                throw IOException("the permissions of $output cannot be kept on its replacement (${e.javaClass.simpleName}: ${e.message})", e)
            }
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal fun writeOutput(text: String, output: Path?, stdout: (String) -> Unit) {
    if (output != null) {
        writeFileAtomically(output, text)
    } else {
        stdout(text)
    }
}

internal fun emitReport(
    report: QualityReport,
    explained: ExplainedQualityReport?,
    formatOpt: String,
    outputOpt: Path?,
    markdownOptions: MarkdownReportOptions = MarkdownReportOptions(),
    stdout: (String) -> Unit,
) {
    val text =
        when (formatOpt.lowercase()) {
            "text" -> explained?.describeText() ?: report.describeText()
            "markdown" -> explained?.describeMarkdown(markdownOptions) ?: report.describeMarkdown(markdownOptions)
            "json" -> findingsToJson(report, explained)
            "turtle" -> JenaBridge.toString(report.underlying.toShaclValidationReportRdf(), "TURTLE")
            else -> throw usageError("Unknown --format $formatOpt (expected text|markdown|json|turtle)")
        }
    writeOutput(text, outputOpt, stdout)
}


private val reportJson = Json { prettyPrint = true }

private fun pitfallLabel(p: PitfallReference?): String? =
    when (p) {
        null -> null
        PitfallReference.Convention -> "convention"
        is PitfallReference.Oops -> "OOPS:${p.number}"
        is PitfallReference.Skos -> "SKOS:${p.number}"
        is PitfallReference.OntoQuality -> "OntoQuality:${p.number}"
        is PitfallReference.KastorExtension -> "Kastor:${p.code}"
    }

/** JSON report built with kotlinx.serialization, so every string (including control characters) is escaped. */
internal fun findingsToJson(
    report: QualityReport,
    explained: ExplainedQualityReport?,
): String {
    val root =
        buildJsonObject {
            putJsonArray("findings") {
                for (f in report.findings) {
                    addJsonObject {
                        put("findingRef", FindingRef.from(f).hexSha256)
                        put("severity", f.violation.severity.name)
                        put("message", f.stableMessage)
                        put("shapeUri", f.violation.shapeUri)
                        put("category", f.category.name)
                        put("pitfall", pitfallLabel(f.pitfall))
                        put("tier", f.tier.name)
                        put("focusNode", focusNodeToString(f.violation.focusNode, f.blankNodeKeys))
                    }
                }
            }
            putJsonArray("llmExplanations") {
                for (e in explained?.explanations.orEmpty()) {
                    addJsonObject {
                        put("findingRef", e.findingRef.hexSha256)
                        put("summary", e.summary)
                        put("whyItMatters", e.whyItMatters)
                        putJsonArray("suggestedActions") { e.suggestedActions.forEach { add(it) } }
                        put("confidenceNote", e.confidenceNote)
                        put("modelId", e.modelId)
                        put("providerKind", e.providerKind)
                        put("promptRunId", e.promptRunId)
                    }
                }
            }
            putJsonArray("llmExplanationFailures") {
                for (failure in explained?.failures.orEmpty()) {
                    addJsonObject {
                        putJsonArray("findingRefs") { failure.findingRefs.forEach { add(it.hexSha256) } }
                        put("reason", failure.reason)
                    }
                }
            }
        }
    return reportJson.encodeToString(JsonObject.serializer(), root)
}

/** Blank nodes use their known stable key (see [com.geoknoesis.kastor.ontoquality.QualityFinding.blankNodeKeys]). */
private fun focusNodeToString(term: RdfTerm, blankNodeKeys: Map<BlankNode, String>): String =
    when (term) {
        is Iri -> term.value
        is BlankNode -> blankNodeKeys[term] ?: term.toString()
        is Literal -> term.lexical
        else -> term.toString()
    }

/**
 * File-system questions of the `--output` pre-check. Tests answer them for situations a test cannot set up on every
 * platform (symbolic links, a file owned by another user).
 */
internal open class OutputFileSystem {
    open fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

    /** The file a symbolic link leads to; fails when a link of the chain is dangling or the chain loops. */
    @Throws(IOException::class)
    open fun realPath(path: Path): Path = path.toRealPath()

    /**
     * Why the existing file [target] cannot be replaced by a new file that keeps its POSIX permissions and owner, or
     * null when it can (or the file system has no POSIX permissions). A probe file is created next to [target], given
     * the permissions of [target], compared by owner and removed: exactly what the atomic write does later.
     */
    open fun replacementProblem(target: Path): String? {
        val permissions =
            try {
                Files.getPosixFilePermissions(target)
            } catch (_: UnsupportedOperationException) {
                return null
            } catch (e: IOException) {
                return "its permissions cannot be read (${e.javaClass.simpleName})"
            }
        val parent = target.parent ?: return null
        val probe = parent.resolve(".${target.fileName}.${java.util.UUID.randomUUID()}.probe")
        return try {
            Files.newOutputStream(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
            val owner = Files.getOwner(target)
            if (owner != Files.getOwner(probe)) {
                return "the file is owned by another user (${owner.name}): replacing it would change its owner; choose another path"
            }
            Files.setPosixFilePermissions(probe, permissions)
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (e: IOException) {
            "a replacement file with the same permissions cannot be created in $parent (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            try {
                Files.deleteIfExists(probe)
            } catch (_: IOException) {
                // Best effort: the probe is empty.
            }
        }
    }
}

/**
 * Why [output] cannot be written, or null when it can: it is a symbolic link that cannot be resolved (dangling, or a
 * loop), a directory, an existing file that is not writable or that cannot be replaced by a file with the same owner
 * and permissions ([OutputFileSystem.replacementProblem]), or its nearest existing parent is not a writable directory
 * (missing parent directories are created when the file is written). Everything [writeFileAtomically] can stumble
 * on is found here, before any work. Nothing is changed (a probe file next to an existing target is removed again).
 */
internal fun outputProblem(output: Path, fileSystem: OutputFileSystem = OutputFileSystem()): String? {
    var target = output.toAbsolutePath().normalize()
    if (fileSystem.isSymbolicLink(target)) {
        target =
            try {
                fileSystem.realPath(target)
            } catch (e: IOException) {
                return "it is a symbolic link whose target does not exist or cannot be resolved (${e.javaClass.simpleName}); " +
                    "create the target or choose another path"
            }
    }
    if (Files.isDirectory(target)) return "it is a directory"
    if (Files.exists(target)) return if (Files.isWritable(target)) fileSystem.replacementProblem(target) else "the file is not writable"
    var ancestor = target.parent ?: return "it has no parent directory"
    while (!Files.exists(ancestor)) ancestor = ancestor.parent ?: return "none of its parent directories exists"
    if (!Files.isDirectory(ancestor)) return "$ancestor is not a directory"
    if (!Files.isWritable(ancestor)) return "directory $ancestor is not writable"
    return null
}

/** True when [a] and [b] name the same file (through links when both exist, else by normalised absolute path). */
internal fun isSameFile(a: Path, b: Path): Boolean {
    val normalised = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
    if (normalised) return true
    return try {
        Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    } catch (_: IOException) {
        false
    }
}
