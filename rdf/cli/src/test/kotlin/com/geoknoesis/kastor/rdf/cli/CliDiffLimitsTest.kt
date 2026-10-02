package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import java.io.PrintStream
import java.nio.charset.Charset
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/** The limits of `diff`, and what it prints, do not depend on parser labels or on the order of the input. */
class CliDiffLimitsTest {
    @TempDir
    lateinit var dir: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun run(runtime: CliRuntime, vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), runtime)
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun file(name: String, content: String): String = dir.resolve(name).also { Files.writeString(it, content) }.toString()

    /** 200 blank nodes, each with a value and a link to the next: the same graph for every [seed], other labels and order. */
    private fun blankGraph(seed: Long): String {
        val random = Random(seed)
        val labels = (0 until 200).map { "n${java.lang.Long.toHexString(random.nextLong())}x$it" }
        val lines = ArrayList<String>()
        for (i in 0 until 200) {
            lines += "_:${labels[i]} <http://e/value> \"v${"%03d".format(i)}\" ."
            lines += "_:${labels[i]} <http://e/next> _:${labels[(i + 1) % 200]} ."
        }
        lines += "<http://e/s> <http://e/p> <http://e/o> ."
        return lines.shuffled(random).joinToString("\n", postfix = "\n")
    }

    @Test
    fun `the sample of blank-node triples is the same whatever the labels and the order of the input`() {
        val other = file("other.nt", "<http://e/s> <http://e/p> <http://e/o> .\n_:z <http://e/value> \"different\" .\n")
        val samples =
            (1L..4L).map { seed ->
                val result = run(CliRuntime(), "diff", file("input.nt", blankGraph(seed)), other)
                assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.err)
                val lines = result.err.lines()
                val start = lines.indexOfFirst { it.contains("triples with blank nodes in input.nt") }
                assertTrue(start >= 0, result.err)
                lines.drop(start + 1).takeWhile { !it.startsWith("---") && !it.startsWith("NOT ISOMORPHIC") }
            }
        assertEquals(65, samples[0].size, "64 lines and the count of the rest: ${samples[0]}")
        assertTrue(samples[0].last().contains("336 more"), samples[0].last())
        for (sample in samples.drop(1)) assertEquals(samples[0], sample)
        // No parser label reaches the output: blank nodes are numbered in order of appearance.
        // (the N-Triples writer may put its own prefix before the number: _:Bb1).
        assertTrue(samples[0].take(64).all { Regex("^_:B?b\\d+ ").containsMatchIn(it) }, samples[0].toString())
        assertFalse(samples[0].any { it.contains("_:n") }, samples[0].toString())
    }

    @Test
    fun `diff takes its search-state limit from --max-search-states`() {
        val a = file("a.ttl", "_:x <http://e/p> <http://e/o> .\n")
        val seen = ArrayList<IsomorphismLimits>()
        val runtime = CliRuntime(isomorphic = { _, _, limits -> seen += limits; true }, nanoTime = { 0L })
        assertEquals(EXIT_OK, run(runtime, "diff", a, a).code)
        assertEquals(EXIT_OK, run(runtime, "diff", "--max-search-states", "5000", a, a).code)
        assertEquals(EXIT_OK, run(runtime, "diff", a, a, "--max-search-states=0", "--timeout", "0").code)
        assertEquals(
            listOf(
                IsomorphismLimits(Duration.ofSeconds(60), 1_000_000),
                IsomorphismLimits(Duration.ofSeconds(60), 5_000),
                IsomorphismLimits(null, null),
            ),
            seen,
        )
        for (bad in listOf(arrayOf("--max-search-states", "-1"), arrayOf("--max-search-states", "many"), arrayOf("--max-search-states"))) {
            val result = run(runtime, "diff", a, a, *bad)
            assertEquals(EXIT_USAGE, result.code, "${bad.toList()}: ${result.err}")
            assertEquals(1, result.err.trim().lines().size, result.err)
        }
        assertEquals(3, seen.size, "a usage error must not start a comparison")
        assertTrue(run(runtime, "help").out.contains("--max-search-states <n>"))
    }

    @Test
    fun `the search-state limit names its own flag, not --timeout`() {
        val a = file("a.ttl", "_:x <http://e/p> <http://e/o> .\n")
        val runtime =
            CliRuntime(
                isomorphic = { _, _, _ ->
                    throw GraphIsomorphismLimitException(GraphIsomorphismLimitException.Reason.SEARCH_STATES, "Graph isomorphism search limit exceeded (1000000 states)")
                },
            )
        val result = run(runtime, "diff", "--timeout", "0", a, a)
        assertEquals(EXIT_RUNTIME_ERROR, result.code, result.err)
        assertTrue(result.err.contains("raise it with --max-search-states <n>, 0 = no limit"), result.err)
        assertTrue(result.err.contains("limit: 1000000 search states"), result.err)
        assertEquals(1, result.err.trim().lines().size, result.err)
    }

    @Test
    fun `an unlimited search really finds a mapping that the default limit of states gives up on`() {
        // Two triangles and one hexagon of indistinguishable nodes on each side, but paired differently.
        fun ring(prefix: String, size: Int): String = (0 until size).joinToString("") { "_:$prefix$it <http://e/n> _:$prefix${(it + 1) % size} .\n" }
        val a = file("a.nt", ring("a", 6) + ring("b", 3) + ring("c", 3))
        val b = file("b.nt", ring("x", 3) + ring("y", 6) + ring("z", 3))
        assertEquals(EXIT_OK, run(CliRuntime(), "diff", "--max-search-states", "0", "--timeout", "0", a, b).code)
        val limited = run(CliRuntime(), "diff", "--max-search-states", "1", a, b)
        assertEquals(EXIT_RUNTIME_ERROR, limited.code, limited.err)
        assertTrue(limited.err.contains("--max-search-states"), limited.err)
    }

    @Test
    fun `the per-graph comparisons of a dataset share one deadline`() {
        // Three named graphs and a default graph without ground differences: the dataset check, then one check per graph.
        fun dataset(label: String): String =
            "_:${label}0 <http://e/p> _:${label}0 .\n" +
                (1..3).joinToString("") { "<http://e/g$it> { _:$label$it <http://e/p> _:$label$it }\n" }
        val a = file("a.trig", dataset("a"))
        val b = file("b.trig", dataset("b"))
        var now = 0L
        val seen = ArrayList<Duration?>()
        val runtime =
            CliRuntime(
                isomorphic = { _, _, limits ->
                    seen += limits.timeout
                    now += Duration.ofSeconds(25).toNanos() // every check takes 25 seconds
                    seen.size > 1 // the dataset as a whole is not isomorphic, each graph on its own is
                },
                nanoTime = { now },
            )
        val result = run(runtime, "diff", "--timeout", "60", a, b)
        assertEquals(listOf(Duration.ofSeconds(60), Duration.ofSeconds(35), Duration.ofSeconds(10)), seen, "each check gets what is left")
        assertEquals(EXIT_RUNTIME_ERROR, result.code, result.err)
        assertTrue(result.err.contains("time limit exceeded") && result.err.contains("60 s for the whole comparison"), result.err)
        assertFalse(result.err.contains("NOT ISOMORPHIC"), "no answer is known at a limit: ${result.err}")

        // Without a limit every check is unlimited, and the answer is given.
        seen.clear()
        val unlimited = run(runtime, "diff", "--timeout", "0", a, b)
        assertEquals(listOf<Duration?>(null, null, null, null, null), seen)
        assertEquals(EXIT_NOT_ISOMORPHIC, unlimited.code, unlimited.err)
        assertTrue(unlimited.err.contains("blank nodes shared across graphs do not correspond"), unlimited.err)
    }

    @Test
    fun `stdout and stderr get their charset independently`() {
        val cp850 = Charset.forName("IBM850")
        val utf8 = Charsets.UTF_8

        fun charsets(out: String?, err: String?, console: Charset?) = standardStreamCharsets(StandardStreamFacts(out, err, console))

        // kastor-rdf diff a b > result.txt : stdout is UTF-8, the messages stay in the console charset.
        assertEquals(StandardStreamCharsets(utf8, cp850), charsets(null, "IBM850", null))
        assertEquals(StandardStreamCharsets(cp850, utf8), charsets("IBM850", null, cp850))
        assertEquals(StandardStreamCharsets(cp850, cp850), charsets("IBM850", "IBM850", cp850))
        assertEquals(StandardStreamCharsets(utf8, utf8), charsets(null, null, null))
        // A JVM without the per-stream properties: the console decides for both streams.
        assertEquals(StandardStreamCharsets(cp850, cp850), charsets(null, null, cp850))
        // JDK 22+: System.console() exists although stdout is redirected; the stream property decides.
        assertEquals(StandardStreamCharsets(utf8, cp850), charsets(null, "IBM850", cp850))
        assertEquals(StandardStreamCharsets(utf8, utf8), charsets("no-such-charset", null, null))
        val here = standardStreamCharsets(StandardStreamFacts.ofThisJvm())
        assertTrue(here.out.name().isNotEmpty() && here.err.name().isNotEmpty())
    }
}
