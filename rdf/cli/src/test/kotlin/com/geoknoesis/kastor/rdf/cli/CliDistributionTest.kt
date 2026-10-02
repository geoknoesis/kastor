package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Runs the application as a user does: the start script of the `installDist` layout (`bin/kastor-rdf`, `lib/`), not the
 * test class path. The build passes the install directory as `kastor.cli.installDir`.
 */
class CliDistributionTest {
    @TempDir
    lateinit var dir: Path

    private val install: Path =
        Path.of(checkNotNull(System.getProperty("kastor.cli.installDir")) { "kastor.cli.installDir is not set: run the tests through Gradle" })

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private class Launch(val code: Int, val out: String, val err: String)

    private fun runScript(vararg args: String): Launch {
        val script = install.resolve("bin").resolve(if (windows) "kastor-rdf.bat" else "kastor-rdf")
        assertTrue(script.isRegularFile(), "start script $script is missing; bin holds ${install.resolve("bin").listDirectoryEntries().map { it.name }}")
        val command = (if (windows) listOf("cmd.exe", "/c", script.toString()) else listOf("sh", script.toString())) + args
        val outFile = dir.resolve("stdout.txt").toFile()
        val errFile = dir.resolve("stderr.txt").toFile()
        val builder = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile)
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.environment().remove("JAVA_OPTS")
        builder.environment().remove("CLASSPATH")
        val process = builder.start()
        process.outputStream.close()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the start script did not exit: $command")
        }
        return Launch(process.exitValue(), outFile.readText(Charsets.UTF_8), errFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `the installed start script is named kastor-rdf and prints help`() {
        val result = runScript("--help")
        assertEquals(0, result.code, result.err)
        assertTrue(result.out.contains("kastor-rdf parse <file>"), result.out)
        assertEquals("", result.err.trim(), "the start script wrote to stderr")
    }

    @Test
    fun `the installed application parses a file and logs through its SLF4J binding`() {
        val file = dir.resolve("data.ttl")
        Files.writeString(file, "<http://example.org/s> <http://example.org/p> \"v\" .\n")
        val result = runScript("parse", file.toString())
        assertEquals(0, result.code, result.err)
        assertTrue(result.out.contains("OK - triples: 1"), result.out)
        assertFalse(result.err.contains("SLF4J"), "no SLF4J binding in the distribution: ${result.err}")
    }

    @Test
    fun `both start scripts load the same class path, in the same order, from one jar`() {
        val name = "kastor-rdf"
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
            assertNotNull(jar.getEntry("com/geoknoesis/kastor/rdf/cli/KastorRdfCli.class"))
        }
    }
}
