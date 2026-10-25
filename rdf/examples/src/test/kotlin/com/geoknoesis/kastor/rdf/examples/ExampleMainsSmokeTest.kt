package com.geoknoesis.kastor.rdf.examples

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Runs every example's `main` with its standard output and standard error captured: an example that throws, never
 * ends, prints nothing or reports a failure it caught itself fails the build instead of the first reader who runs it.
 * Examples report a caught failure with [reportFailure], whose fixed marker ([EXAMPLE_FAILURE_MARKER]) is looked for
 * in both streams whatever the failure's own message says; a stack trace on either stream fails the example too.
 */
class ExampleMainsSmokeTest {

    /** The facade class of every source file under src/main that declares a top-level `main`. */
    private fun exampleMains(): List<String> {
        val sources = Path.of("src/main/kotlin")
        check(Files.isDirectory(sources)) { "run from the :rdf:examples project directory (Gradle does): ${sources.toAbsolutePath()}" }
        return Files.walk(sources).use { paths ->
            paths.filter { it.extension == "kt" && Regex("""(?m)^fun main\(""").containsMatchIn(it.readText()) }
                .map { file ->
                    val pkg = Regex("""(?m)^package ([\w.]+)""").find(file.readText())!!.groupValues[1]
                    "$pkg.${file.fileName.toString().removeSuffix(".kt")}Kt"
                }
                .sorted()
                .toList()
        }
    }

    @Test
    fun `every example is run`() {
        // A new example is picked up by the scan; this guards the scan itself.
        assertEquals(9, exampleMains().size, "examples found: ${exampleMains()}")
    }

    @TestFactory
    fun `each example main runs to the end and reports no failure`(): List<DynamicTest> =
        exampleMains().map { className ->
            DynamicTest.dynamicTest(className.substringAfterLast('.')) {
                val (output, errors) = run(className)
                assertTrue(output.isNotBlank(), "$className printed nothing")
                for ((stream, text) in listOf("stdout" to output, "stderr" to errors)) {
                    for (failure in FAILURE_MARKERS) {
                        assertFalse(text.contains(failure), "$className reported a failure on $stream ('$failure'):\n$text")
                    }
                }
            }
        }

    @Test
    fun `a failure an example reports carries a marker the smoke test looks for`() {
        val (output, _) = capture { reportFailure("a step", IllegalStateException("no such provider")) }
        assertTrue(FAILURE_MARKERS.any { output.contains(it) }, output)
    }

    /** Runs the example; returns what it printed on standard output and on standard error. */
    private fun run(className: String): Pair<String, String> {
        val main = Class.forName(className).getMethod("main", Array<String>::class.java)
        return capture { out ->
            assertTimeoutPreemptively(Duration.ofMinutes(2)) {
                try {
                    main.invoke(null, emptyArray<String>())
                } catch (e: InvocationTargetException) {
                    throw AssertionError("$className threw ${e.cause}\n--- output ---\n${out.toString(Charsets.UTF_8)}", e.cause)
                }
            }
        }
    }

    /** Runs [block] with standard output and standard error captured; returns both. */
    private fun capture(block: (ByteArrayOutputStream) -> Unit): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            block(out)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return out.toString(Charsets.UTF_8) to err.toString(Charsets.UTF_8)
    }

    private companion object {
        /** A caught failure ([reportFailure]), the markers examples printed before it, and a stack trace line. */
        val FAILURE_MARKERS = listOf(EXAMPLE_FAILURE_MARKER, "✗ Failed", "ERROR", "Exception", "\tat ")
    }
}
