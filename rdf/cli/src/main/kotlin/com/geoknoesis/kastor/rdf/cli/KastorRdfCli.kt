@file:JvmName("KastorRdfCli")

package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.WeisfeilerLehmanIsomorphism
import com.geoknoesis.kastor.rdf.serialize
import org.apache.jena.riot.system.ErrorHandlerFactory
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.inputStream
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
    installParseErrorReporting()
    val code = exitCodeAfterFlush(runCli(args.toList(), out, err), out, err)
    if (code != 0) exitProcess(code)
}

/**
 * Makes Jena RIOT report parse errors only through the exception the CLI prints ("Parse error in <file>: ..."): its
 * default handler also logs each error, which printed it a second time without saying which input it was in.
 * Warnings (data that parses but is suspect) are still logged. This changes a Jena-wide default, so only the
 * application's [main] calls it, never [runCli].
 */
internal fun installParseErrorReporting() {
    ErrorHandlerFactory.setDefaultErrorHandler(ErrorHandlerFactory.errorHandlerWarnOrExceptions(ErrorHandlerFactory.stdLogger))
}

/**
 * The Java feature release the CLI is compiled for. The start scripts' launcher (`KastorRdfLauncher`, compiled for
 * Java 8 in its own source set) refuses older ones with the same number; CliJavaVersionTest keeps the two equal.
 */
internal const val REQUIRED_JAVA_VERSION = 21

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

internal class CliError(message: String, val code: Int = EXIT_USAGE) : RuntimeException(message)

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
            Usage: kastor-rdf parse [--base <iri>] [--] <file> [FORMAT]

            Parses <file> and prints the number of triples (and named graphs). FORMAT defaults from the file extension.
            --base <iri>: absolute IRI that relative IRIs in <file> are resolved against (without it they are an error).
            The whole input is loaded in memory; very large dumps are not streamed.
            Use -- before a file name that starts with a dash.
            """.trimIndent(),
        "to-turtle" to
            """
            Usage: kastor-rdf to-turtle [--base <iri>] [--] <file> [INPUT_FORMAT]

            Prints <file> as Turtle. Inputs with named graphs (TriG, N-Quads, JSON-LD) are refused with status 1:
            Turtle cannot represent them, and they are never dropped silently.
            --base <iri>: absolute IRI that relative IRIs in <file> are resolved against (without it they are an error).
            The whole input is loaded in memory; very large dumps are not streamed.
            Use -- before a file name that starts with a dash.
            """.trimIndent(),
        "diff" to
            """
            Usage: kastor-rdf diff [--timeout <seconds>] [--max-search-states <n>] [--base <iri>] [--] <file1> <file2> [FORMAT]

            Compares two files up to blank node relabelling: exit 0 isomorphic, 2 not isomorphic, 3 stopped at a limit.
            Graphs named by blank nodes (TriG _:g { }, N-Quads _:g) match up to relabelling like any blank node.
            --timeout <seconds>: wall-clock limit (default $DEFAULT_DIFF_TIMEOUT_SECONDS; 0 = no limit).
            --max-search-states <n>: backtracking limit (default $DEFAULT_DIFF_SEARCH_STATES; 0 = no limit).
            --base <iri>: absolute IRI that relative IRIs in both files are resolved against (without it they are an error).
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

internal fun oneLine(text: String?): String = (text ?: "").lines().joinToString(" ") { it.trim() }.trim()

internal fun expectAtMost(rest: List<String>, max: Int) {
    if (rest.size > max) throw CliError("Unexpected argument: ${rest[max]}")
}

private fun printUsage(out: PrintStream) {
    out.println(
        """
        kastor-rdf - small RDF utilities (requires Java $REQUIRED_JAVA_VERSION or later, and Jena on the classpath via rdf-cli).

        Usage:
          kastor-rdf help
          kastor-rdf parse [--base <iri>] <file> [FORMAT]
          kastor-rdf to-turtle [--base <iri>] <file> [INPUT_FORMAT]
          kastor-rdf diff [--timeout <seconds>] [--max-search-states <n>] [--base <iri>] [--] <file1> <file2> [FORMAT]
          kastor-rdf <command> --help       per-command help; -- ends the options (files starting with a dash)
        Inputs are loaded in memory (no streaming): very large dumps need a correspondingly large heap.

        Exit status: 0 success; 1 usage or input error (bad or extra arguments, unknown format, missing file,
        parse error); 2 diff found the inputs not isomorphic; 3 runtime error (I/O, RDF provider, internal, out of memory,
        a Java older than $REQUIRED_JAVA_VERSION).
        An input that cannot be read to its end (I/O error) is 3, not a parse error. diff also exits 3 when the
        comparison is stopped by its limit (no answer is known). A limit reached after the answer is known, while
        comparing the graphs of a dataset one by one to say where they differ, leaves them unexplained: still 2.
        onto-qa uses a different convention: 0 success; 1 findings at or above --severity; 2 the ontology could not
        be parsed; 3 LLM explanations failed (with --fail-on-explain-error); 4 usage or configuration error;
        5 runtime error.

        Output redirected to a file or a pipe is UTF-8, whatever the platform charset. On an interactive console the
        console's own charset is used (characters it cannot show are printed as '?'; on Windows, 'chcp 65001'
        switches the console to UTF-8).

        FORMAT defaults from the file extension when omitted (.ttl -> TURTLE, .nt -> NTRIPLES, .nq -> NQUADS,
        .trig -> TRIG, .jsonld/.json -> JSON-LD, .rdf/.owl/.xml -> RDFXML); other extensions require FORMAT.
        Every input is read as a dataset: the formats that can name graphs (TriG, N-Quads, JSON-LD) keep their named
        graphs in every command. diff compares the default graph and all named graphs as one dataset (blank nodes shared across graphs must correspond). A graph named by a blank node
        matches a graph named by a blank node in the other input, up to relabelling; graphs named by IRIs must have
        the same IRIs.

        Relative IRIs are an error unless --base <iri> names the base to resolve them against. The location of the
        file is never used as its base: the same document must read the same wherever it is stored. A parse error
        names the file it is in.

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
    val arguments = FileArguments(rest)
    val input = read(arguments.path, formatOrInfer(arguments.path, arguments.format), runtime, arguments.baseIri)
    val graphs = if (input.namedGraphs.isEmpty()) "" else ", named graphs: ${input.namedGraphs.size}"
    out.println("OK - triples: ${input.tripleCount}$graphs")
    return 0
}

private fun cmdToTurtle(rest: List<String>, out: PrintStream, runtime: CliRuntime): Int {
    val arguments = FileArguments(rest)
    val input = read(arguments.path, formatOrInfer(arguments.path, arguments.format), runtime, arguments.baseIri)
    if (input.namedGraphs.isNotEmpty()) {
        throw CliError("Input has ${input.namedGraphs.size} named graph(s), which Turtle cannot represent.")
    }
    out.print(input.defaultGraph.serialize(RdfFormat.TURTLE))
    return 0
}
