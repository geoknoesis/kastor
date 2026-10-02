package com.geoknoesis.kastor.ontoquality.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Starts `onto-qa` the way its start script does: a new JVM running the `mainClass` configured in
 * `build.gradle.kts` (exposed by the build as `kastor-cli-launch.properties`), so a main class that does not exist
 * fails here. The child JVM is given a non-UTF-8 platform charset for stdout / stderr, like a Windows console.
 */
class CliLaunchTest {
    @TempDir lateinit var dir: Path

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
        // An argument file keeps the command line short: the class path exceeds the Windows limit.
        val classPath = System.getProperty("java.class.path").replace("\\", "\\\\")
        val argFile = dir.resolve("java.args")
        Files.writeString(argFile, "-cp \"$classPath\"\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(java, "-Xmx384m", "-Dstdout.encoding=windows-1252", "-Dstderr.encoding=windows-1252", "@$argFile", configuredMainClass()) + args
        val outFile = dir.resolve("stdout.bin").toFile()
        val errFile = dir.resolve("stderr.bin").toFile()
        val builder = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile)
        // The child must never reach an LLM provider.
        builder.environment().remove(LLM_EXPLAIN_ENV)
        val process = builder.start()
        process.outputStream.close()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
            // A slow machine is told apart from a hung child by what the child wrote before it was stopped.
            val written = listOf(outFile, errFile).joinToString("\n") { "--- ${it.name} ---\n" + it.readBytes().toString(Charsets.UTF_8).takeLast(4_000) }
            error("onto-qa did not exit within 180 s: $command\n$written")
        }
        return Launch(process.exitValue(), outFile.readBytes(), errFile.readBytes())
    }

    @Test
    fun `the configured main class starts and prints help`() {
        val result = launch("--help")
        assertEquals(EXIT_OK, result.code, result.errText)
        assertTrue(result.outText.contains("Usage: onto-qa"), result.outText)
        assertFalse(result.errText.contains("ClassNotFoundException"), result.errText)
    }

    @Test
    fun `JSON reports on stdout are UTF-8 whatever the platform charset`() {
        val file = dir.resolve("onto.ttl")
        Files.writeString(file, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<http://example.org/café#中文€> a owl:Class .\n")
        val result = launch("check", file.toString(), "--catalog", "owl-quality", "--severity", "info", "--format", "json")
        assertEquals(EXIT_FINDINGS, result.code, result.errText)
        val focusNodes =
            Json.parseToJsonElement(result.outText).jsonObject.getValue("findings").jsonArray.map {
                it.jsonObject.getValue("focusNode").jsonPrimitive.content
            }
        assertTrue("http://example.org/café#中文€" in focusNodes, "stdout is not UTF-8: $focusNodes")
    }

    @Test
    fun `messages on stderr are UTF-8 whatever the platform charset`() {
        val file = dir.resolve("broken.ttl")
        Files.writeString(file, "<http://example.org/s> <http://example.org/p> café中文:x .\n")
        val result = launch("metrics", file.toString())
        assertEquals(EXIT_INPUT_ERROR, result.code, result.errText)
        assertTrue(result.errText.contains("café中文"), "stderr is not UTF-8: ${result.errText}")
    }
    /** cmd.exe rejects a line of more than 8191 characters ("The input line is too long"). */
    @Test
    fun `the Windows start script has no line that cmd cannot run`() {
        val properties = Properties()
        javaClass.getResourceAsStream("/kastor-cli-launch.properties")!!.use { properties.load(it) }
        val name = properties.getProperty("applicationName")
        val script = checkNotNull(javaClass.getResourceAsStream("/$name.bat")) { "$name.bat is not generated" }.use { it.readBytes().toString(Charsets.UTF_8) }
        val longest = script.lines().maxOf { it.length }
        assertTrue(longest < 8000, "the longest line of $name.bat has $longest characters")
        assertTrue(script.contains(configuredMainClass()), "the start script does not start ${configuredMainClass()}")
    }
}
