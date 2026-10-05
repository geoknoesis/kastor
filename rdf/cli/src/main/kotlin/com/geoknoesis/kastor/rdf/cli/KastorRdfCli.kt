@file:JvmName("KastorRdfCli")

package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.WeisfeilerLehmanIsomorphism
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.serialize
import java.io.BufferedOutputStream
import java.io.CharConversionException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.UTFDataFormatException
import java.io.UncheckedIOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.PriorityQueue
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Redirected output (a file, a pipe) is UTF-8 whatever the platform charset: a Windows code page would turn
    // unmappable characters into '?'. An interactive console is written in its own charset, or it would show UTF-8
    // bytes as garbage unless the user had switched it to UTF-8 (chcp 65001). Each stream is decided on its own:
    // with `kastor-rdf diff a b > result.txt` stdout is UTF-8 while stderr, still on the console, keeps its charset.
    val charsets = standardStreamCharsets(StandardStreamFacts.ofThisJvm())
    val out = printStream(FileOutputStream(FileDescriptor.out), charsets.out)
    val err = printStream(FileOutputStream(FileDescriptor.err), charsets.err)
    System.setOut(out)
    System.setErr(err)
    val code = exitCodeAfterFlush(runCli(args.toList(), out, err), out, err)
    if (code != 0) exitProcess(code)
}

/**
 * [code], unless standard output could not be written: a [PrintStream] swallows [IOException]s (a closed pipe, a full
 * disk) and only [PrintStream.checkError] tells, so a result that never arrived must not exit 0.
 */
internal fun exitCodeAfterFlush(code: Int, out: PrintStream, err: PrintStream): Int {
    val outputLost = out.checkError() // flushes first
    err.flush()
    if (!outputLost) return code
    try {
        err.println("kastor-rdf: error: could not write to standard output")
        err.flush()
    } catch (_: Throwable) {
        // Nothing more can be reported; the status still tells.
    }
    return EXIT_RUNTIME_ERROR
}

/** A line-flushed [PrintStream] over [stream], writing [charset]. */
internal fun printStream(stream: OutputStream, charset: Charset): PrintStream = PrintStream(BufferedOutputStream(stream), true, charset)

/** The charsets of stdout and stderr. */
internal data class StandardStreamCharsets(val out: Charset, val err: Charset)

/**
 * What the JVM knows about its standard streams.
 *
 * @param stdoutTerminalEncoding `sun.stdout.encoding`: set by the JVM only when stdout is a terminal (its encoding).
 * @param stderrTerminalEncoding `sun.stderr.encoding`: the same for stderr.
 * @param consoleCharset the charset of the interactive console ([interactiveConsoleCharset]), null without one.
 */
internal class StandardStreamFacts(
    val stdoutTerminalEncoding: String?,
    val stderrTerminalEncoding: String?,
    val consoleCharset: Charset?,
) {
    companion object {
        fun ofThisJvm(): StandardStreamFacts =
            StandardStreamFacts(
                stdoutTerminalEncoding = property("sun.stdout.encoding"),
                stderrTerminalEncoding = property("sun.stderr.encoding"),
                consoleCharset = interactiveConsoleCharset(),
            )

        private fun property(name: String): String? =
            try {
                System.getProperty(name)?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
    }
}

/**
 * The charset of each standard stream, decided per stream: the console charset when **that** stream is a terminal,
 * UTF-8 when it is redirected.
 *
 * The JVM tells which stream is a terminal through `sun.stdout.encoding` / `sun.stderr.encoding` (it sets each one
 * only when the stream is attached to a terminal). When neither is set but there is an interactive console (a JVM
 * that does not provide the properties), the console only tells that stdin and stdout are terminals; stderr is then
 * written like stdout.
 */
internal fun standardStreamCharsets(facts: StandardStreamFacts): StandardStreamCharsets {
    val perStream = facts.stdoutTerminalEncoding != null || facts.stderrTerminalEncoding != null

    fun charset(terminalEncoding: String?): Charset =
        when {
            terminalEncoding != null -> charsetOrNull(terminalEncoding) ?: facts.consoleCharset ?: Charsets.UTF_8
            perStream -> Charsets.UTF_8
            else -> standardStreamCharset(facts.consoleCharset)
        }
    return StandardStreamCharsets(out = charset(facts.stdoutTerminalEncoding), err = charset(facts.stderrTerminalEncoding))
}

private fun charsetOrNull(name: String): Charset? =
    try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }

/** The charset of a stream when only the console is known: the charset of the console when there is one, else UTF-8. */
internal fun standardStreamCharset(consoleCharset: Charset?): Charset = consoleCharset ?: Charsets.UTF_8

/**
 * The charset of the interactive console this JVM is attached to, or null when there is none (or the charset cannot
 * be determined). `System.console()` is null when the standard streams are redirected; on JDK 22 and later it can be
 * non-null even then, and `Console.isTerminal()` (called reflectively, it does not exist before) tells.
 */
internal fun interactiveConsoleCharset(): Charset? {
    val console = System.console() ?: return null
    val terminal =
        try {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as? Boolean ?: true
        } catch (_: ReflectiveOperationException) {
            true
        }
    if (!terminal) return null
    return try {
        console.charset()
    } catch (_: RuntimeException) {
        null
    }
}

/** Parsed input: the default graph plus named graphs (empty for graph formats). */
private class ParsedInput(val defaultGraph: RdfGraph, val namedGraphs: Map<String, RdfGraph>) {
    val tripleCount: Int get() = defaultGraph.size() + namedGraphs.values.sumOf { it.size() }

    /**
     * The dataset as one graph: every quad becomes `<graph> <urn:kastor:rdf-cli:contains> <<( s p o )>>`, so one graph
     * isomorphism check maps blank nodes consistently across the default graph and all named graphs.
     * Default-graph quads use a distinct predicate so no input graph IRI can alias their encoding.
     *
     * It is a view: the quads are wrapped as they are read and the graphs are not copied into a second indexed graph.
     */
    fun asQuadGraph(): RdfGraph = QuadView(listOf(null to defaultGraph) + namedGraphs.map { Iri(it.key) to it.value })

    private class QuadView(private val graphs: List<Pair<Iri?, RdfGraph>>) : RdfGraph {
        private val byName = graphs.toMap()

        override fun getTriplesSequence(): Sequence<RdfTriple> =
            graphs.asSequence().flatMap { (name, graph) -> graph.getTriplesSequence().map { RdfTriple(name ?: DEFAULT_GRAPH, if (name == null) DEFAULT_CONTAINS else CONTAINS, TripleTerm(it)) } }

        override fun getTriples(): List<RdfTriple> = getTriplesSequence().toList()

        override fun hasTriple(triple: RdfTriple): Boolean {
            val quad = triple.obj as? TripleTerm ?: return false
            val name = triple.subject as? Iri ?: return false
            return when (triple.predicate) {
                DEFAULT_CONTAINS -> name == DEFAULT_GRAPH && byName[null]?.hasTriple(quad.triple) == true
                CONTAINS -> byName[name]?.hasTriple(quad.triple) == true
                else -> false
            }
        }

        override fun size(): Int = graphs.sumOf { it.second.size() }
    }

    private companion object {
        val DEFAULT_GRAPH = Iri("urn:kastor:rdf-cli:default-graph")
        val CONTAINS = Iri("urn:kastor:rdf-cli:contains")
        val DEFAULT_CONTAINS = Iri("urn:kastor:rdf-cli:default-contains")
    }
}

/** Exit status: success (for `diff`, the inputs are isomorphic). */
internal const val EXIT_OK = 0

/** Exit status: usage error (bad or extra arguments, unknown format, missing file) or unparsable input. */
internal const val EXIT_USAGE = 1

/** Exit status: `diff` found the inputs not isomorphic. */
internal const val EXIT_NOT_ISOMORPHIC = 2

/**
 * Exit status: runtime failure (I/O error, also in the middle of reading an input; `diff` stopped at its isomorphism
 * limit; RDF provider failure, internal error, out of memory, stack overflow).
 */
internal const val EXIT_RUNTIME_ERROR = 3

/** Default wall-clock limit of the `diff` isomorphism check, in seconds (`--timeout`). */
internal const val DEFAULT_DIFF_TIMEOUT_SECONDS = 60L

/** Default limit of the backtracking assignments the `diff` isomorphism check may try (`--max-search-states`). */
internal const val DEFAULT_DIFF_SEARCH_STATES = 1_000_000

/**
 * Limits of one isomorphism check.
 *
 * @param timeout wall-clock limit; null for none.
 * @param maxSearchStates backtracking assignments; null for no limit, which also lifts the work budget that otherwise
 *   scales with the size of the input.
 */
internal data class IsomorphismLimits(val timeout: Duration?, val maxSearchStates: Int? = DEFAULT_DIFF_SEARCH_STATES)

private class CliError(message: String, val code: Int = EXIT_USAGE) : RuntimeException(message)

/** What the commands use to read files and compare graphs; tests replace them. */
internal class CliRuntime(
    val open: (Path) -> InputStream = { it.inputStream() },
    /** Graph isomorphism within [IsomorphismLimits]; throws [GraphIsomorphismLimitException] at a limit. */
    val isomorphic: (RdfGraph, RdfGraph, IsomorphismLimits) -> Boolean = { a, b, limits ->
        val states = limits.maxSearchStates
        WeisfeilerLehmanIsomorphism(states ?: Int.MAX_VALUE, if (states == null) Long.MAX_VALUE else null, limits.timeout).areIsomorphic(a, b)
    },
    /** Nanosecond clock of the overall `diff` deadline. */
    val nanoTime: () -> Long = System::nanoTime,
)

/** Runs the CLI and returns the process exit code ([EXIT_OK], [EXIT_USAGE], [EXIT_NOT_ISOMORPHIC], [EXIT_RUNTIME_ERROR]). */
internal fun runCli(args: List<String>, out: PrintStream, err: PrintStream, runtime: CliRuntime = CliRuntime()): Int {
    if (args.isEmpty()) {
        printUsage(out)
        return EXIT_USAGE
    }
    return try {
        if (args[0] in COMMAND_USAGE && helpRequested(args.drop(1))) {
            out.println(COMMAND_USAGE.getValue(args[0]))
            return EXIT_OK
        }
        when (args[0]) {
            "help", "--help", "-h" -> {
                expectAtMost(args.drop(1), 0)
                printUsage(out)
                EXIT_OK
            }
            "parse" -> cmdParse(args.drop(1), out, runtime)
            "to-turtle" -> cmdToTurtle(args.drop(1), out, runtime)
            "diff" -> cmdDiff(args.drop(1), out, err, runtime)
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

/** True when `--help` or `-h` appears before a `--` terminator. */
private fun helpRequested(rest: List<String>): Boolean = rest.takeWhile { it != "--" }.any { it == "--help" || it == "-h" }

private val COMMAND_USAGE =
    mapOf(
        "parse" to
            """
            Usage: kastor-rdf parse [--] <file> [FORMAT]

            Parses <file> and prints the number of triples (and named graphs). FORMAT defaults from the file extension.
            The whole input is loaded in memory; very large dumps are not streamed.
            Use -- before a file name that starts with a dash.
            """.trimIndent(),
        "to-turtle" to
            """
            Usage: kastor-rdf to-turtle [--] <file> [INPUT_FORMAT]

            Prints <file> as Turtle. Inputs with named graphs are refused (Turtle cannot represent them).
            The whole input is loaded in memory; very large dumps are not streamed.
            Use -- before a file name that starts with a dash.
            """.trimIndent(),
        "diff" to
            """
            Usage: kastor-rdf diff [--timeout <seconds>] [--max-search-states <n>] [--] <file1> <file2> [FORMAT]

            Compares two files up to blank node relabelling: exit 0 isomorphic, 2 not isomorphic, 3 stopped at a limit.
            --timeout <seconds>: wall-clock limit (default $DEFAULT_DIFF_TIMEOUT_SECONDS; 0 = no limit).
            --max-search-states <n>: backtracking limit (default $DEFAULT_DIFF_SEARCH_STATES; 0 = no limit).
            Both inputs are loaded in memory; very large dumps are not streamed.
            Use -- before a file name that starts with a dash.
            """.trimIndent(),
    )

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
          kastor-rdf diff [--timeout <seconds>] [--max-search-states <n>] [--] <file1> <file2> [FORMAT]
          kastor-rdf <command> --help       per-command help; -- ends the options (files starting with a dash)
        Inputs are loaded in memory (no streaming): very large dumps need a correspondingly large heap.

        Exit status: 0 success; 1 usage or input error (bad or extra arguments, unknown format, missing file,
        parse error); 2 diff found the inputs not isomorphic; 3 runtime error (I/O, RDF provider, internal, out of memory).
        An input that cannot be read to its end (I/O error) is 3, not a parse error. diff also exits 3 when the
        comparison is stopped by its limit (no answer is known).
        onto-qa uses a different convention: 0 success; 1 findings at or above --severity; 2 the ontology could not
        be parsed; 3 LLM explanations failed (with --fail-on-explain-error); 4 usage or configuration error;
        5 runtime error.

        Output redirected to a file or a pipe is UTF-8, whatever the platform charset. On an interactive console the
        console's own charset is used (characters it cannot show are printed as '?'; on Windows, 'chcp 65001'
        switches the console to UTF-8).

        FORMAT defaults from the file extension when omitted (.ttl -> TURTLE, .nt -> NTRIPLES, .nq -> NQUADS,
        .trig -> TRIG, .jsonld/.json -> JSON-LD, .rdf/.owl/.xml -> RDFXML); other extensions require FORMAT.
        Quad formats (TriG, N-Quads) are read as datasets: diff compares the default graph and all named
        graphs as one dataset (blank nodes shared across graphs must correspond).

        diff --timeout <seconds>: wall-clock limit of the blank-node matching (default $DEFAULT_DIFF_TIMEOUT_SECONDS; 0 = no limit).
        It is one limit for the whole comparison: for datasets, the per-graph comparisons share what is left of it.
        diff --max-search-states <n>: backtracking assignments the matching may try (default $DEFAULT_DIFF_SEARCH_STATES; 0 = no limit,
        which also lifts the work budget that otherwise scales with the size of the inputs).
        When the inputs are not isomorphic, diff prints up to $SAMPLE_LINES of the triples found in only one input, per input.
        Blank nodes in that sample are numbered in order of appearance (_:b1, _:b2, ...), so the same inputs give the
        same lines whatever labels their parser made up.

        Examples:
          ./gradlew :rdf:cli:run --args="parse data/example.ttl"
          ./gradlew :rdf:cli:run --args="to-turtle data/example.nt NT"
          ./gradlew :rdf:cli:run --args="diff expected.ttl actual.ttl"
          kastor-rdf diff --timeout 600 expected.trig actual.trig
        """.trimIndent(),
    )
}

private fun cmdParse(rest: List<String>, out: PrintStream, runtime: CliRuntime): Int {
    val (path, format) = parseFileArgs(rest)
    val input = read(path, formatOrInfer(path, format), runtime)
    val graphs = if (input.namedGraphs.isEmpty()) "" else ", named graphs: ${input.namedGraphs.size}"
    out.println("OK - triples: ${input.tripleCount}$graphs")
    return 0
}

private fun cmdToTurtle(rest: List<String>, out: PrintStream, runtime: CliRuntime): Int {
    val (path, format) = parseFileArgs(rest)
    val input = read(path, formatOrInfer(path, format), runtime)
    if (input.namedGraphs.isNotEmpty()) {
        throw CliError("Input has ${input.namedGraphs.size} named graph(s), which Turtle cannot represent.")
    }
    out.print(input.defaultGraph.serialize(RdfFormat.TURTLE))
    return 0
}

/**
 * `diff` arguments: `--timeout <seconds>` and `--max-search-states <n>` (or `--option=<value>`) anywhere, then two
 * files and an optional format.
 */
private class DiffArguments(rest: List<String>) {
    val positional = ArrayList<String>()

    /** Wall-clock limit of the isomorphism check; null for none (`--timeout 0`). */
    var timeout: Duration? = Duration.ofSeconds(DEFAULT_DIFF_TIMEOUT_SECONDS)
        private set

    /** Backtracking assignments the isomorphism check may try; null for no limit (`--max-search-states 0`). */
    var maxSearchStates: Int? = DEFAULT_DIFF_SEARCH_STATES
        private set

    init {
        var i = 0
        while (i < rest.size) {
            val arg = rest[i]
            when {
                arg == "--" -> {
                    positional += rest.drop(i + 1)
                    break
                }
                arg == "--timeout" -> {
                    timeout = seconds(rest.getOrNull(i + 1) ?: throw CliError("--timeout requires a number of seconds (0 = no limit)"))
                    i++
                }
                arg.startsWith("--timeout=") -> timeout = seconds(arg.substringAfter('='))
                arg == "--max-search-states" -> {
                    maxSearchStates = states(rest.getOrNull(i + 1) ?: throw CliError("--max-search-states requires a number (0 = no limit)"))
                    i++
                }
                arg.startsWith("--max-search-states=") -> maxSearchStates = states(arg.substringAfter('='))
                arg.startsWith("--") -> throw CliError("Unknown option: $arg")
                else -> positional += arg
            }
            i++
        }
        if (positional.size < 2) throw CliError("diff requires two file paths")
        expectAtMost(positional, 3)
    }

    private fun seconds(value: String): Duration? {
        val seconds = value.toLongOrNull()?.takeIf { it in 0..MAX_TIMEOUT_SECONDS }
            ?: throw CliError("--timeout must be a whole number of seconds from 0 (no limit) to $MAX_TIMEOUT_SECONDS, not '${oneLine(value)}'")
        return if (seconds == 0L) null else Duration.ofSeconds(seconds)
    }

    private fun states(value: String): Int? {
        val states = value.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw CliError("--max-search-states must be a whole number from 0 (no limit) to ${Int.MAX_VALUE}, not '${oneLine(value)}'")
        return if (states == 0) null else states
    }

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 31_536_000L
    }
}

private fun cmdDiff(rest: List<String>, out: PrintStream, err: PrintStream, runtime: CliRuntime): Int {
    val arguments = DiffArguments(rest)
    val fmt = arguments.positional.getOrNull(2)
    val p1 = requireRegular(Path.of(arguments.positional[0]))
    val p2 = requireRegular(Path.of(arguments.positional[1]))
    val format1 = formatOrInfer(p1, fmt)
    val format2 = formatOrInfer(p2, fmt)
    val first = read(p1, format1, runtime)
    val second = read(p2, format2, runtime)
    val timeout = arguments.timeout
    val maxSearchStates = arguments.maxSearchStates
    // One deadline for the whole comparison: a dataset is compared as a whole and then, to say where it differs,
    // graph by graph. Each of those checks gets what is left of --timeout, not the full limit again.
    val started = runtime.nanoTime()

    fun stopped(reason: GraphIsomorphismLimitException.Reason, message: String?): CliError {
        val advice =
            when (reason) {
                GraphIsomorphismLimitException.Reason.TIME ->
                    " (limit: ${timeout?.seconds ?: 0} s for the whole comparison; raise it with --timeout <seconds>, 0 = no limit)"
                GraphIsomorphismLimitException.Reason.SEARCH_STATES ->
                    " (limit: ${maxSearchStates ?: 0} search states; raise it with --max-search-states <n>, 0 = no limit; --timeout does not change it)"
                GraphIsomorphismLimitException.Reason.WORK ->
                    " (work limit, which scales with the size of the inputs; --max-search-states 0 lifts it, --timeout does not change it)"
                else -> " (${reason.name.lowercase().replace('_', ' ')} limit; --timeout and --max-search-states do not change it)"
            }
        return CliError("kastor-rdf: error: diff stopped without an answer: ${oneLine(message)}$advice", EXIT_RUNTIME_ERROR)
    }

    fun isomorphic(a: RdfGraph, b: RdfGraph): Boolean {
        val remaining =
            timeout?.let {
                val left = it.minusNanos(runtime.nanoTime() - started)
                if (left.isZero || left.isNegative) throw stopped(GraphIsomorphismLimitException.Reason.TIME, "Graph isomorphism time limit exceeded")
                left
            }
        return try {
            runtime.isomorphic(a, b, IsomorphismLimits(remaining, maxSearchStates))
        } catch (e: GraphIsomorphismLimitException) {
            throw stopped(e.reason, e.message)
        }
    }

    // One isomorphism check decides: on the graphs themselves, or (datasets) on the quads, so that blank nodes shared
    // across graphs must correspond.
    val sameNames = first.namedGraphs.keys == second.namedGraphs.keys
    val dataset = first.namedGraphs.isNotEmpty() || second.namedGraphs.isNotEmpty()
    val same =
        sameNames &&
            if (dataset) isomorphic(first.asQuadGraph(), second.asQuadGraph()) else isomorphic(first.defaultGraph, second.defaultGraph)
    if (same) {
        out.println(
            "ISOMORPHIC - datasets match up to blank node relabelling " +
                "(${first.tripleCount} triples in first file, ${second.tripleCount} in second).",
        )
        return EXIT_OK
    }

    val differences = mutableListOf<String>()
    if (!sameNames) {
        differences += "named graphs differ: ${first.namedGraphs.keys.sorted()} vs ${second.namedGraphs.keys.sorted()}"
    }
    val pairs = listOf<Pair<String, Pair<RdfGraph, RdfGraph>>>("(default graph)" to (first.defaultGraph to second.defaultGraph)) +
        first.namedGraphs.keys.intersect(second.namedGraphs.keys).sorted()
            .map { "<$it>" to (first.namedGraphs.getValue(it) to second.namedGraphs.getValue(it)) }
    for ((label, graphs) in pairs) {
        val left = DifferenceSample(graphs.first, graphs.second)
        val right = DifferenceSample(graphs.second, graphs.first)
        // Different triples without blank nodes, or different sizes, settle it; otherwise (datasets only: for graph
        // formats the check above already answered) the graph is compared on its own.
        val differs =
            left.onlyHere > 0 || right.onlyHere > 0 || graphs.first.size() != graphs.second.size() ||
                !dataset || !isomorphic(graphs.first, graphs.second)
        if (differs) {
            differences += "$label is not isomorphic"
            left.print(err, label, p1.name)
            right.print(err, label, p2.name)
        }
    }
    if (differences.isEmpty()) {
        differences += "every graph is isomorphic on its own, but blank nodes shared across graphs do not correspond"
    }
    err.println("NOT ISOMORPHIC")
    differences.forEach { err.println("  $it") }
    return EXIT_NOT_ISOMORPHIC
}

/** Lines of a difference sample shown per input and graph. */
private const val SAMPLE_LINES = 64

/**
 * What [graph] has that [other] lacks: the [SAMPLE_LINES] smallest triples without blank nodes that are not in
 * [other], found in one pass with a bounded priority queue (nothing is sorted or serialised in full).
 *
 * When there are none, the sample is of the triples with blank nodes (their labels cannot be compared across files,
 * so all of them are candidates). Parser labels differ on every parse, so that sample is chosen and printed without
 * them ([blankSample]): two more passes over the graph, made only when the sample is printed.
 */
private class DifferenceSample(private val graph: RdfGraph, other: RdfGraph) {
    private val ground = PriorityQueue(SAMPLE_LINES + 1, compareByDescending<Pair<String, RdfTriple>> { it.first })

    /** Triples without blank nodes that [other] does not have. */
    var onlyHere = 0
        private set
    private var withBlankNodes = 0

    init {
        for (t in graph.getTriplesSequence()) {
            if (hasBlankNode(t.subject) || hasBlankNode(t.obj)) {
                withBlankNodes++
            } else if (!other.hasTriple(t)) {
                onlyHere++
                offer(ground, "${t.subject} ${t.predicate} ${t.obj}", t)
            }
        }
    }

    private fun offer(queue: PriorityQueue<Pair<String, RdfTriple>>, key: String, t: RdfTriple) {
        if (queue.size < SAMPLE_LINES) {
            queue += key to t
        } else if (key < queue.peek().first) {
            queue.poll()
            queue += key to t
        }
    }

    private fun hasBlankNode(term: RdfTerm): Boolean =
        term is BlankNode || (term is TripleTerm && (hasBlankNode(term.triple.subject) || hasBlankNode(term.triple.obj)))

    /** [term] with every blank node written by [blank] (labels never reach the text). */
    private fun render(term: RdfTerm, blank: (BlankNode) -> String): String =
        when (term) {
            is BlankNode -> blank(term)
            is TripleTerm -> "<<( ${render(term.triple.subject, blank)} ${term.triple.predicate} ${render(term.triple.obj, blank)} )>>"
            else -> term.toString()
        }

    private fun render(t: RdfTriple, blank: (BlankNode) -> String): String = "${render(t.subject, blank)} ${t.predicate} ${render(t.obj, blank)}"

    private fun blankNodes(term: RdfTerm, into: MutableList<BlankNode>) {
        when (term) {
            is BlankNode -> into += term
            is TripleTerm -> {
                blankNodes(term.triple.subject, into)
                blankNodes(term.triple.obj, into)
            }
            else -> Unit
        }
    }

    /**
     * The [SAMPLE_LINES] smallest triples with blank nodes in an order that does not depend on labels, with their
     * blank nodes renamed `b1`, `b2`, … in order of appearance.
     *
     * A blank node is first described by its triples (position, predicate and the other term, other blank nodes left
     * anonymous), accumulated into one number per node; a triple is then ordered by its text with every blank node
     * replaced by that number. Triples that this cannot tell apart (structurally alike nodes) may be taken in either
     * order: they print as the same lines.
     */
    private fun blankSample(): List<RdfTriple> {
        val signatures = HashMap<BlankNode, Long>()
        val nodes = ArrayList<BlankNode>(2)
        for (t in graph.getTriplesSequence()) {
            nodes.clear()
            blankNodes(t.subject, nodes)
            blankNodes(t.obj, nodes)
            if (nodes.isEmpty()) continue
            val shape = render(t) { "_:" }
            nodes.forEachIndexed { position, node -> signatures.merge(node, hash("$position $shape"), Long::plus) }
        }
        val queue = PriorityQueue(SAMPLE_LINES + 1, compareByDescending<Pair<String, RdfTriple>> { it.first })
        for (t in graph.getTriplesSequence()) {
            if (!hasBlankNode(t.subject) && !hasBlankNode(t.obj)) continue
            offer(queue, render(t) { "_:" + java.lang.Long.toHexString(signatures.getValue(it)) }, t)
        }
        val names = HashMap<BlankNode, BlankNode>()

        fun rename(term: RdfTerm): RdfTerm =
            when (term) {
                is BlankNode -> names.getOrPut(term) { BlankNode("b${names.size + 1}") }
                is TripleTerm -> TripleTerm(RdfTriple(rename(term.triple.subject) as RdfResource, term.triple.predicate, rename(term.triple.obj)))
                else -> term
            }
        return queue.sortedBy { it.first }.map { (_, t) -> RdfTriple(rename(t.subject) as RdfResource, t.predicate, rename(t.obj)) }
    }

    /** FNV-1a, 64 bits: a fixed hash, so the order of the sample is the same in every JVM. */
    private fun hash(text: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in text) {
            h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return h
    }

    fun print(err: PrintStream, label: String, file: String) {
        val blank = onlyHere == 0
        val total = if (blank) withBlankNodes else onlyHere
        if (total == 0) return
        val sample = if (blank) blankSample() else ground.map { it.second }
        val title =
            if (blank) {
                "triples with blank nodes in $file (numbered in order of appearance); its other triples are all in the other input"
            } else {
                "triples only in $file"
            }
        err.println("--- $label: $title (sorted N-Triples, first lines) ---")
        val lines = MemoryGraph(sample).serialize(RdfFormat.N_TRIPLES).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        lines.forEach(err::println)
        if (total > sample.size) err.println("... (${total - sample.size} more)")
    }
}

private fun read(path: Path, format: String, runtime: CliRuntime): ParsedInput {
    var stream: IoTrackingInputStream? = null
    try {
        val provider = JenaProvider()
        val input = IoTrackingInputStream(runtime.open(path)).also { stream = it }
        return if (RdfFormat.isQuadFormat(format)) {
            JenaRepository.MemoryRepository().use { repo ->
                input.use { provider.parseDataset(repo, it, format) }
                ParsedInput(
                    MemoryGraph(repo.defaultGraph.getTriples()),
                    repo.listGraphs().associate { name: Iri -> name.value to MemoryGraph(repo.getGraph(name).getTriples()) },
                )
            }
        } else {
            ParsedInput(input.use { provider.parseGraph(it, format) }, emptyMap())
        }
    } catch (e: Exception) {
        // A parser reports a read that failed half-way like a syntax error; the file could not be read, which is a
        // runtime failure (status 3), not an error in its content (status 1).
        val failure = stream?.failure ?: ioFailureIn(e) ?: throw e
        throw CliError(
            "kastor-rdf: error: I/O error reading $path: ${failure.javaClass.simpleName}: ${oneLine(failure.message ?: "(no message)")}",
            EXIT_RUNTIME_ERROR,
        )
    }
}

/** Remembers an [IOException] raised while reading, so a read failure is not reported as a syntax error. */
private class IoTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var failure: IOException? = null
        private set

    override fun read(): Int =
        try {
            super.read()
        } catch (e: IOException) {
            failure = e
            throw e
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try {
            super.read(b, off, len)
        } catch (e: IOException) {
            failure = e
            throw e
        }
}

/**
 * The I/O failure in the cause chain of [e], if any: a JDK [IOException] (or what wraps one: [UncheckedIOException],
 * Jena's `RuntimeIOException`). Exceptions that derive from [IOException] but report bad content are not I/O
 * failures: character decoding errors, and the parse exceptions of libraries (JSON parsers) that extend it.
 */
internal fun ioFailureIn(e: Throwable): Throwable? {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth++ < 32) {
        val next = current.cause?.takeIf { it !== current }
        when (current) {
            is CharacterCodingException, is CharConversionException, is UTFDataFormatException -> return null
            is UncheckedIOException -> if (next == null) return current
            is IOException -> return if (current.javaClass.name.startsWith("java.")) current else null
            else -> if (current.javaClass.name == JENA_RUNTIME_IO_EXCEPTION && next == null) return current
        }
        current = next
    }
    return null
}

private const val JENA_RUNTIME_IO_EXCEPTION = "org.apache.jena.atlas.RuntimeIOException"

private fun parseFileArgs(rest: List<String>): Pair<Path, String?> {
    val args = if (rest.firstOrNull() == "--") rest.drop(1) else rest
    if (args.isEmpty()) throw CliError("Missing file path")
    expectAtMost(args, 2)
    return requireRegular(Path.of(args[0])) to args.getOrNull(1)
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
