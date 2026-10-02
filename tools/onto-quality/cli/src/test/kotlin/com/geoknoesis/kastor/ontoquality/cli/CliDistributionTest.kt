package com.geoknoesis.kastor.ontoquality.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the application as a user does: the start script of the `installDist` layout (`bin/onto-qa`, `lib/`), not the
 * test class path. The build passes the install directory as `kastor.cli.installDir`.
 */
class CliDistributionTest {
    @TempDir lateinit var dir: Path

    private val install: Path =
        Path.of(checkNotNull(System.getProperty("kastor.cli.installDir")) { "kastor.cli.installDir is not set: run the tests through Gradle" })

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private class Launch(val code: Int, val out: String, val err: String)

    private fun runScript(vararg args: String): Launch {
        val script = install.resolve("bin").resolve(if (windows) "onto-qa.bat" else "onto-qa")
        assertTrue(script.isRegularFile(), "start script $script is missing; bin holds ${install.resolve("bin").listDirectoryEntries().map { it.name }}")
        val command = (if (windows) listOf("cmd.exe", "/c", script.toString()) else listOf("sh", script.toString())) + args
        val outFile = dir.resolve("stdout.txt").toFile()
        val errFile = dir.resolve("stderr.txt").toFile()
        val builder = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile)
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.environment()["JAVA_OPTS"] = "-Xmx384m"
        builder.environment().remove("CLASSPATH")
        // The child must never reach an LLM provider.
        builder.environment().remove(LLM_EXPLAIN_ENV)
        val process = builder.start()
        process.outputStream.close()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the start script did not exit: $command")
        }
        return Launch(process.exitValue(), outFile.readText(Charsets.UTF_8), errFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `the installed start script is named onto-qa and prints help`() {
        val result = runScript("--help")
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("Usage: onto-qa"), result.out)
        assertEquals("", result.err.trim(), "the start script wrote to stderr")
    }

    @Test
    fun `the installed application computes metrics through the launcher class path`() {
        val file = dir.resolve("onto.ttl")
        Files.writeString(file, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<http://example.org/A> a owl:Class .\n")
        val result = runScript("metrics", file.toString(), "--format", "json")
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("totalNamedClasses"), result.out)
        assertFalse(result.err.contains("SLF4J"), "no SLF4J binding in the distribution: ${result.err}")
    }

    @Test
    fun `both start scripts load the same class path, in the same order, from one jar`() {
        val name = "onto-qa"
        val unix = Files.readString(install.resolve("bin").resolve(name))
        val bat = Files.readString(install.resolve("bin").resolve("$name.bat"))
        val unixClassPath = Regex("(?m)^CLASSPATH=(.*)$").find(unix)!!.groupValues[1].trim()
        val batClassPath = Regex("(?m)^set CLASSPATH=(.*)$").find(bat)!!.groupValues[1].trim()
        assertFalse(unixClassPath.contains('*') || batClassPath.contains('*'), "wildcards load jars in directory order: $unixClassPath / $batClassPath")
        val jarName = unixClassPath.substringAfterLast('/')
        assertEquals("\$APP_HOME/lib/$jarName", unixClassPath)
        assertEquals("%APP_HOME%\\lib\\$jarName", batClassPath)

        val lib = install.resolve("lib")
        JarFile(lib.resolve(jarName).toFile()).use { launcher ->
            val classPath = launcher.manifest.mainAttributes.getValue("Class-Path").split(' ').filter { it.isNotBlank() }
            val jars = lib.listDirectoryEntries("*.jar").map { it.name }.filter { it != jarName }.toSet()
            assertEquals(jars, classPath.toSet(), "the launcher jar lists exactly the jars of lib/")
            assertEquals(classPath.size, classPath.toSet().size)
            assertNotNull(launcher.getEntry("simplelogger.properties"), "the logging configuration belongs to the application")
        }
    }

    @Test
    fun `the published library jar carries no logging configuration`() {
        val jarName = checkNotNull(System.getProperty("kastor.cli.libraryJar")) { "kastor.cli.libraryJar is not set" }
        JarFile(install.resolve("lib").resolve(jarName).toFile()).use { jar ->
            assertNull(jar.getEntry("simplelogger.properties"), "simplelogger.properties in $jarName would shadow a consumer's own")
            assertNotNull(jar.getEntry("com/geoknoesis/kastor/ontoquality/cli/MainKt.class"))
        }
    }
}
