@file:JvmName("KastorRdfCli")

package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.isIsomorphicTo
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.serialize
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // RDF and messages are written as UTF-8 whatever the platform charset: a Windows code page would turn
    // unmappable characters into '?' when the output is redirected to a file.
    val out = utf8PrintStream(FileOutputStream(FileDescriptor.out))
    val err = utf8PrintStream(FileOutputStream(FileDescriptor.err))
    System.setOut(out)
    System.setErr(err)
    val code = runCli(args.toList(), out, err)
    out.flush()
    err.flush()
    if (code != 0) exitProcess(code)
}

/** A line-flushed UTF-8 [PrintStream] over [stream]. */
internal fun utf8PrintStream(stream: OutputStream): PrintStream = PrintStream(BufferedOutputStream(stream), true, Charsets.UTF_8)

/** Parsed input: the default graph plus named graphs (empty for graph formats). */
private class ParsedInput(val defaultGraph: RdfGraph, val namedGraphs: Map<String, RdfGraph>) {
    val tripleCount: Int get() = defaultGraph.size() + namedGraphs.values.sumOf { it.size() }

    /**
     * The dataset as one graph: every quad becomes `<graph> <urn:kastor:rdf-cli:contains> <<( s p o )>>`, so one graph
     * isomorphism check maps blank nodes consistently across the default graph and all named graphs.
     */
    fun asQuadGraph(): RdfGraph =
        MemoryGraph(
            (listOf(DEFAULT_GRAPH to defaultGraph) + namedGraphs.map { Iri(it.key) to it.value }).flatMap { (name, graph) ->
                graph.getTriples().map { RdfTriple(name, CONTAINS, TripleTerm(it)) }
            },
        )

    private companion object {
        val DEFAULT_GRAPH = Iri("urn:kastor:rdf-cli:default-graph")
        val CONTAINS = Iri("urn:kastor:rdf-cli:contains")
    }
}

/** Exit status: success (for `diff`, the inputs are isomorphic). */
internal const val EXIT_OK = 0

/** Exit status: usage error (bad or extra arguments, unknown format, missing file) or unparsable input. */
internal const val EXIT_USAGE = 1

/** Exit status: `diff` found the inputs not isomorphic. */
internal const val EXIT_NOT_ISOMORPHIC = 2

/** Exit status: runtime failure (I/O error, RDF provider failure, internal error, out of memory, stack overflow). */
internal const val EXIT_RUNTIME_ERROR = 3

private class CliError(message: String, val code: Int = EXIT_USAGE) : RuntimeException(message)

/** Runs the CLI and returns the process exit code ([EXIT_OK], [EXIT_USAGE], [EXIT_NOT_ISOMORPHIC], [EXIT_RUNTIME_ERROR]). */
internal fun runCli(args: List<String>, out: PrintStream, err: PrintStream): Int {
    if (args.isEmpty()) {
        printUsage(out)
        return EXIT_USAGE
    }
    return try {
        when (args[0]) {
            "help", "--help", "-h" -> {
                expectAtMost(args.drop(1), 0)
                printUsage(out)
                EXIT_OK
            }
            "parse" -> cmdParse(args.drop(1), out)
            "to-turtle" -> cmdToTurtle(args.drop(1), out)
            "diff" -> cmdDiff(args.drop(1), out, err)
            else -> {
                err.println("Unknown command: ${args[0]}")
                printUsage(err)
                EXIT_USAGE
            }
        }
    } catch (e: CliError) {
        err.println(e.message)
        e.code
    } catch (e: RdfFormatException) {
        err.println("Parse error: ${oneLine(e.message)}")
        EXIT_USAGE
    } catch (e: InvalidPathException) {
        err.println("Invalid path: ${oneLine(e.message)}")
        EXIT_USAGE
    } catch (e: Throwable) {
        // Errors as well (OutOfMemoryError, StackOverflowError, LinkageError): the JVM default is a stack trace and
        // status 1, which this tool reserves for usage and input errors.
        exitCodeForFailure(e, err)
    }
}

/** Unexpected failures (I/O, provider or internal errors, JVM errors): one line on [err], [EXIT_RUNTIME_ERROR]. */
internal fun exitCodeForFailure(e: Throwable, err: PrintStream): Int {
    try {
        err.println("kastor-rdf: error: ${e.javaClass.simpleName}: ${oneLine(e.message ?: "(no message)")}")
    } catch (_: Throwable) {
        // Nothing more can be reported (for example no memory left to build the message); the status still tells.
    }
    return EXIT_RUNTIME_ERROR
}

private fun oneLine(text: String?): String = (text ?: "").lines().joinToString(" ") { it.trim() }.trim()

private fun expectAtMost(rest: List<String>, max: Int) {
    if (rest.size > max) throw CliError("Unexpected argument: ${rest[max]}")
}

private fun printUsage(out: PrintStream) {
    out.println(
        """
        kastor-rdf - small RDF utilities (requires Jena on the classpath via rdf-cli).

        Usage:
          kastor-rdf help
          kastor-rdf parse <file> [FORMAT]
          kastor-rdf to-turtle <file> [INPUT_FORMAT]
          kastor-rdf diff <file1> <file2> [FORMAT]

        Exit status: 0 success; 1 usage or input error (bad or extra arguments, unknown format, missing file,
        parse error); 2 diff found the inputs not isomorphic; 3 runtime error (I/O, RDF provider, internal, out of memory).
        onto-qa uses a different convention: 0 success; 1 findings at or above --severity; 2 the ontology could not
        be parsed; 3 LLM explanations failed (with --fail-on-explain-error); 4 usage or configuration error;
        5 runtime error.

        Output on stdout and stderr is UTF-8, whatever the platform charset.

        FORMAT defaults from the file extension when omitted (.ttl -> TURTLE, .nt -> NTRIPLES, .nq -> NQUADS,
        .trig -> TRIG, .jsonld/.json -> JSON-LD, .rdf/.owl/.xml -> RDFXML); other extensions require FORMAT.
        Quad formats (TriG, N-Quads) are read as datasets: diff compares the default graph and all named
        graphs as one dataset (blank nodes shared across graphs must correspond).

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
    out.println("OK - triples: ${input.tripleCount}$graphs")
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
    expectAtMost(rest, 3)
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
        if (!graphs.first.isIsomorphicTo(graphs.second)) {
            differences += "$label is not isomorphic"
            err.println("--- $label in ${p1.name} (sorted N-Triples, first lines) ---")
            err.println(sortedNTriplesSnippet(graphs.first))
            err.println("--- $label in ${p2.name} (sorted N-Triples, first lines) ---")
            err.println(sortedNTriplesSnippet(graphs.second))
        }
    }
    // Graph-by-graph isomorphism ignores blank nodes shared across graphs; the dataset must match as a whole.
    if (differences.isEmpty() && !first.asQuadGraph().isIsomorphicTo(second.asQuadGraph())) {
        differences += "every graph is isomorphic on its own, but blank nodes shared across graphs do not correspond"
    }
    if (differences.isEmpty()) {
        out.println(
            "ISOMORPHIC - datasets match up to blank node relabelling " +
                "(${first.tripleCount} triples in first file, ${second.tripleCount} in second).",
        )
        return 0
    }
    err.println("NOT ISOMORPHIC")
    differences.forEach { err.println("  $it") }
    return EXIT_NOT_ISOMORPHIC
}

private const val SNIPPET_LINES = 64

/** The first [SNIPPET_LINES] lines of [graph] as sorted N-Triples (blank node labels are implementation-specific). */
private fun sortedNTriplesSnippet(graph: RdfGraph): String {
    val lines = graph.serialize(RdfFormat.N_TRIPLES).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.sorted().toList()
    val head = lines.take(SNIPPET_LINES).joinToString("\n")
    val omitted = lines.size - SNIPPET_LINES
    return if (omitted > 0) "$head\n... ($omitted more lines)" else head
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
    expectAtMost(rest, 2)
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
