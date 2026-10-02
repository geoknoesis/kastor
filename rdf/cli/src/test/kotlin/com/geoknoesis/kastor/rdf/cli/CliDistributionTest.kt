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

    /** The end of what a child process wrote, for failure messages. */
    private fun tail(file: java.io.File): String =
        try {
            file.readText(Charsets.UTF_8).takeLast(4_000).ifEmpty { "(nothing)" }
        } catch (e: java.io.IOException) {
            "(unreadable: ${e.message})"
        }

    private class Launch(val code: Int, val out: String, val err: String)

    /**
     * The command line that starts [script]. On Windows `cmd.exe /c <script> <args>` breaks as soon as the script path
     * and an argument both contain a space: cmd strips the first and the last quote of the whole line. The reliable
     * form is `cmd /s /c ""<script>" <args>"`: with `/s` exactly the outer pair of quotes is removed. The whole inner
     * line is one process argument (already quoted, so Java passes it through unchanged).
     */
    private fun startCommand(script: Path, args: List<String>): List<String> {
        if (!windows) return listOf("sh", script.toString()) + args
        val inner =
            (listOf(script.toString()) + args).joinToString(" ") { arg ->
                require('"' !in arg) { "a quote in an argument cannot be passed to cmd: $arg" }
                if (arg.any { it.isWhitespace() || it in "&()[]{}^=;!'+,~" }) "\"$arg\"" else arg
            }
        return listOf("cmd.exe", "/d", "/s", "/c", "\"$inner\"")
    }

    private fun runScript(vararg args: String, installation: Path = install): Launch {
        val script = installation.resolve("bin").resolve(if (windows) "kastor-rdf.bat" else "kastor-rdf")
        assertTrue(script.isRegularFile(), "start script $script is missing; bin holds ${installation.resolve("bin").listDirectoryEntries().map { it.name }}")
        val command = startCommand(script, args.toList())
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
            process.waitFor(30, TimeUnit.SECONDS)
            // A slow machine is told apart from a hung child by what the child wrote before it was stopped.
            error("the start script did not exit within 120 s: $command\n--- stdout ---\n${tail(outFile)}\n--- stderr ---\n${tail(errFile)}")
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

    /**
     * The installation in a directory whose name has a space, next to the real one (same volume): the start scripts
     * are copied, the jars hard-linked (copied where the file system has no hard links).
     */
    private fun installationWithSpace(): Path {
        val spaced = Files.createTempDirectory(install.toAbsolutePath().parent, "install with space ")
        for (sub in listOf("bin", "lib")) {
            val target = Files.createDirectories(spaced.resolve(sub))
            for (entry in install.resolve(sub).listDirectoryEntries()) {
                val copy = target.resolve(entry.name)
                if (sub == "bin") {
                    Files.copy(entry, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                } else {
                    try {
                        Files.createLink(copy, entry)
                    } catch (_: Exception) {
                        Files.copy(entry, copy)
                    }
                }
            }
        }
        return spaced
    }

    @Test
    fun `the start script runs from a directory with a space and takes a path with a space`() {
        val data = Files.createDirectories(dir.resolve("my data"))
        val file = data.resolve("data one.ttl")
        Files.writeString(file, "<http://example.org/s> <http://example.org/p> \"v\" .\n")
        val spaced = installationWithSpace()
        try {
            val result = runScript("parse", file.toString(), installation = spaced)
            assertEquals(0, result.code, result.err)
            assertTrue(result.out.contains("OK - triples: 1"), result.out)
        } finally {
            spaced.toFile().deleteRecursively()
        }
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
