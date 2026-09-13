@file:JvmName("KastorRdfCli")

package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.testing.RdfGraphIsomorphism
import com.geoknoesis.kastor.rdf.testing.RdfGraphSnapshots
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = runCli(args.toList(), System.out, System.err)
    if (code != 0) exitProcess(code)
}

/** Parsed input: the default graph plus named graphs (empty for graph formats). */
private class ParsedInput(val defaultGraph: RdfGraph, val namedGraphs: Map<String, RdfGraph>) {
    val tripleCount: Int get() = defaultGraph.size() + namedGraphs.values.sumOf { it.size() }
}

private class CliError(message: String, val code: Int = 1) : RuntimeException(message)

/** Runs the CLI and returns the process exit code (0 ok, 1 usage/input error, 2 diff mismatch). */
internal fun runCli(args: List<String>, out: PrintStream, err: PrintStream): Int {
    if (args.isEmpty()) {
        printUsage(out)
        return 1
    }
    return try {
        when (args[0]) {
            "help", "--help", "-h" -> { printUsage(out); 0 }
            "parse" -> cmdParse(args.drop(1), out)
            "to-turtle" -> cmdToTurtle(args.drop(1), out)
            "diff" -> cmdDiff(args.drop(1), out, err)
            else -> {
                err.println("Unknown command: ${args[0]}")
                printUsage(err)
                1
            }
        }
    } catch (e: CliError) {
        err.println(e.message)
        e.code
    } catch (e: RdfFormatException) {
        err.println("Parse error: ${e.message}")
        1
    }
}

private fun printUsage(out: PrintStream) {
    out.println(
        """
        kastor-rdf — small RDF utilities (requires Jena on the classpath via rdf-cli).

        Usage:
          kastor-rdf help
          kastor-rdf parse <file> [FORMAT]
          kastor-rdf to-turtle <file> [INPUT_FORMAT]
          kastor-rdf diff <file1> <file2> [FORMAT]

        FORMAT defaults from the file extension when omitted (.ttl → TURTLE, .nt → NTRIPLES, .nq → NQUADS,
        .trig → TRIG, .jsonld/.json → JSON-LD, .rdf/.owl/.xml → RDFXML); other extensions require FORMAT.
        Quad formats (TriG, N-Quads) are read as datasets: diff compares the default graph and every
        named graph separately.

        Examples:
          ./gradlew :rdf:cli:run --args="parse data/example.ttl"
          ./gradlew :rdf:cli:run --args="to-turtle data/example.nt NT"
          ./gradlew :rdf:cli:run --args="diff expected.ttl actual.ttl"
        """.trimIndent(),
    )
}

private fun cmdParse(rest: List<String>, out: PrintStream): Int {
    val (path, format) = parseFileArgs(rest)
    val input = read(path, formatOrInfer(path, format))
    val graphs = if (input.namedGraphs.isEmpty()) "" else ", named graphs: ${input.namedGraphs.size}"
    out.println("OK — triples: ${input.tripleCount}$graphs")
    return 0
}

private fun cmdToTurtle(rest: List<String>, out: PrintStream): Int {
    val (path, format) = parseFileArgs(rest)
    val input = read(path, formatOrInfer(path, format))
    if (input.namedGraphs.isNotEmpty()) {
        throw CliError("Input has ${input.namedGraphs.size} named graph(s), which Turtle cannot represent.")
    }
    out.print(input.defaultGraph.serialize(RdfFormat.TURTLE))
    return 0
}

private fun cmdDiff(rest: List<String>, out: PrintStream, err: PrintStream): Int {
    if (rest.size < 2) throw CliError("diff requires two file paths")
    val fmt = rest.getOrNull(2)
    val p1 = requireRegular(Path.of(rest[0]))
    val p2 = requireRegular(Path.of(rest[1]))
    val first = read(p1, formatOrInfer(p1, fmt))
    val second = read(p2, formatOrInfer(p2, fmt))

    val differences = mutableListOf<String>()
    if (first.namedGraphs.keys != second.namedGraphs.keys) {
        differences += "named graphs differ: ${first.namedGraphs.keys.sorted()} vs ${second.namedGraphs.keys.sorted()}"
    }
    val pairs = listOf<Pair<String, Pair<RdfGraph, RdfGraph>>>("(default graph)" to (first.defaultGraph to second.defaultGraph)) +
        first.namedGraphs.keys.intersect(second.namedGraphs.keys).sorted()
            .map { "<$it>" to (first.namedGraphs.getValue(it) to second.namedGraphs.getValue(it)) }
    for ((label, graphs) in pairs) {
        if (!RdfGraphIsomorphism.isIsomorphic(graphs.first, graphs.second)) {
            differences += "$label is not isomorphic"
            err.println("--- $label in ${p1.name} (sorted N-Triples, first lines) ---")
            err.println(RdfGraphSnapshots.formatSnippet(graphs.first))
            err.println("--- $label in ${p2.name} (sorted N-Triples, first lines) ---")
            err.println(RdfGraphSnapshots.formatSnippet(graphs.second))
        }
    }
    if (differences.isEmpty()) {
        out.println(
            "ISOMORPHIC — datasets match up to blank node relabelling " +
                "(${first.tripleCount} triples in first file, ${second.tripleCount} in second).",
        )
        return 0
    }
    err.println("NOT ISOMORPHIC")
    differences.forEach { err.println("  $it") }
    return 2
}

private fun read(path: Path, format: String): ParsedInput {
    val provider = JenaProvider()
    return if (RdfFormat.isQuadFormat(format)) {
        JenaRepository.MemoryRepository().use { repo ->
            path.inputStream().use { provider.parseDataset(repo, it, format) }
            ParsedInput(
                MemoryGraph(repo.defaultGraph.getTriples()),
                repo.listGraphs().associate { name: Iri -> name.value to MemoryGraph(repo.getGraph(name).getTriples()) },
            )
        }
    } else {
        ParsedInput(path.inputStream().use { provider.parseGraph(it, format) }, emptyMap())
    }
}

private fun parseFileArgs(rest: List<String>): Pair<Path, String?> {
    if (rest.isEmpty()) throw CliError("Missing file path")
    return requireRegular(Path.of(rest[0])) to rest.getOrNull(1)
}

private fun requireRegular(path: Path): Path {
    if (!path.isRegularFile()) throw CliError("Not a regular file: $path")
    return path
}

private fun formatOrInfer(path: Path, explicit: String?): String {
    if (explicit != null) {
        return RdfFormat.fromString(explicit)?.formatName ?: throw CliError("Unknown format: $explicit")
    }
    return when (path.extension.lowercase()) {
        "ttl" -> "TURTLE"
        "nt" -> "NTRIPLES"
        "nq" -> "NQUADS"
        "trig" -> "TRIG"
        "jsonld", "json" -> "JSON-LD"
        "rdf", "owl", "xml" -> "RDFXML"
        else -> throw CliError("Cannot infer the RDF format of '${path.name}'; pass FORMAT explicitly")
    }
}
