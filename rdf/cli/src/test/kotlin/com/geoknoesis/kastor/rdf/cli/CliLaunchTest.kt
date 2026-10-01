package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Starts the application the way its start script does: a new JVM running the `mainClass` configured in
 * `build.gradle.kts` (exposed by the build as `kastor-cli-launch.properties`), so a main class that does not exist
 * fails here. The child JVM is given a non-UTF-8 platform charset for stdout / stderr, like a Windows console.
 */
class CliLaunchTest {
    @TempDir
    lateinit var dir: Path

    private class Launch(val code: Int, val out: ByteArray, val err: ByteArray) {
        val outText: String get() = out.toString(Charsets.UTF_8)
        val errText: String get() = err.toString(Charsets.UTF_8)
    }

    private fun configuredMainClass(): String {
        val properties = Properties()
        val resource = checkNotNull(javaClass.getResourceAsStream("/kastor-cli-launch.properties")) { "kastor-cli-launch.properties is not generated" }
        resource.use { properties.load(it) }
        return properties.getProperty("mainClass")
    }

    private fun launch(vararg args: String): Launch {
        // An argument file keeps the command line short: the class path can exceed the Windows limit.
        val classPath = System.getProperty("java.class.path").replace("\\", "\\\\")
        val argFile = dir.resolve("java.args")
        Files.writeString(argFile, "-cp \"$classPath\"\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(java, "-Xmx256m", "-Dstdout.encoding=windows-1252", "-Dstderr.encoding=windows-1252", "@$argFile", configuredMainClass()) + args
        val outFile = dir.resolve("stdout.bin").toFile()
        val errFile = dir.resolve("stderr.bin").toFile()
        val process = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile).start()
        process.outputStream.close()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("CLI did not exit: $command")
        }
        return Launch(process.exitValue(), outFile.readBytes(), errFile.readBytes())
    }

    @Test
    fun `the configured main class starts and prints help`() {
        val result = launch("--help")
        assertEquals(0, result.code, result.errText)
        assertTrue(result.outText.contains("kastor-rdf parse <file>"), result.outText)
        assertFalse(result.errText.contains("ClassNotFoundException"), result.errText)
    }

    @Test
    fun `stdout is UTF-8 whatever the platform charset`() {
        val text = "café 中文 €"
        val file = dir.resolve("data.nt")
        Files.writeString(file, "<http://example.org/s> <http://example.org/p> \"$text\" .\n")
        val result = launch("to-turtle", file.toString())
        assertEquals(0, result.code, result.errText)
        assertTrue(result.outText.contains(text), "stdout is not UTF-8: ${result.out.joinToString(" ") { "%02x".format(it) }}")
    }

    @Test
    fun `stderr is UTF-8 whatever the platform charset`() {
        val text = "café 中文 €"
        val first = dir.resolve("a.nt")
        val second = dir.resolve("b.nt")
        Files.writeString(first, "<http://example.org/s> <http://example.org/p> \"$text\" .\n")
        Files.writeString(second, "<http://example.org/s> <http://example.org/p> \"other\" .\n")
        val result = launch("diff", first.toString(), second.toString())
        assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.errText)
        assertTrue(result.errText.contains(text), "stderr is not UTF-8: ${result.errText}")
    }
}
