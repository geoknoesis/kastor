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
 * Runs every example's `main` with its output captured: an example that throws, never ends, prints nothing or reports
 * a failure it caught itself ("✗ Failed", "ERROR") fails the build instead of the first reader who runs it.
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
        assertEquals(8, exampleMains().size, "examples found: ${exampleMains()}")
    }

    @TestFactory
    fun `each example main runs to the end and reports no failure`(): List<DynamicTest> =
        exampleMains().map { className ->
            DynamicTest.dynamicTest(className.substringAfterLast('.')) {
                val output = run(className)
                assertTrue(output.isNotBlank(), "$className printed nothing")
                for (failure in listOf("✗ Failed", "ERROR", "Exception")) {
                    assertFalse(output.contains(failure), "$className reported a failure ('$failure'):\n$output")
                }
            }
        }

    private fun run(className: String): String {
        val main = Class.forName(className).getMethod("main", Array<String>::class.java)
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            assertTimeoutPreemptively(Duration.ofMinutes(2)) {
                try {
                    main.invoke(null, emptyArray<String>())
                } catch (e: InvocationTargetException) {
                    throw AssertionError("$className threw ${e.cause}\n--- output ---\n${captured.toString(Charsets.UTF_8)}", e.cause)
                }
            }
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8)
    }
}
