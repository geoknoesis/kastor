package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * The reviewer's probe that took ShaclSail 51 s (4 nested property shapes over a 20,000-statement graph whose nodes
 * each have 100 values), run by `Rdf4jValidation()` in a child JVM with a 192 MiB heap. The Kastor SHACL validator
 * evaluates a nested shape once per value node, so it decides the probe in under a second (a violation, as ShaclSail
 * found after 51 s). The deadline itself is exercised deterministically by [deadline error]; any input the validator
 * cannot decide in time stops there with an [EmbeddedShapesRefusedException].
 */
@OptIn(com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi::class)
class Rdf4jFanOutDeadlineTest {
    @Test
    @Timeout(120)
    fun `the 51-second fan-out probe stops at the deadline in a small heap`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-Xmx192m", "-cp", System.getProperty("java.class.path"), FanOutProbe::class.java.name)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), output)
        val line = output.lines().firstOrNull { it.startsWith("RESULT ") } ?: error(output)
        assertTrue(line.startsWith("RESULT Violations ") || (line.startsWith("RESULT refused ") && "timed out" in line), line)
        val millis = line.substringAfter("ms=").substringBefore(' ').toLong()
        // The deadline is 5 s; allow for a slow, loaded machine, far below ShaclSail's 51 s.
        assertTrue(millis < 20_000, line)
    }

    @Test
    fun `deadline error`() {
        val graph = Rdf.parse(FanOutProbe.turtle(2, 3), "TURTLE")
        val validator = Rdf4jValidation()
        // The same pipeline as Rdf4jValidation(), with a deadline that has always passed.
        val native = Rdf4jNativeEmbedded(
            1, false, { }, Rdf4jValidation.Companion::toStatements, Rdf4jValidation.Companion::extractEmbeddedShapes, validator::toResult,
            timeout = java.time.Duration.ofNanos(1),
        )
        val focus = Iri("http://ex/a")
        val error = kotlin.test.assertFailsWith<EmbeddedShapesRefusedException> {
            native.validateAll(graph, mapOf(focus to org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI(focus.value)))
        }
        assertTrue("timed out" in error.message!! && "trustingEmbeddedShapes()" in error.message!!, error.message)
        assertTrue(error.cause is com.geoknoesis.kastor.rdf.shacl.ShaclValidationException, "${error.cause}")
        native.close()
        validator.close()
    }
}

/** Child-JVM entry point of [Rdf4jFanOutDeadlineTest]. */
object FanOutProbe {
    fun turtle(levels: Int, k: Int): String = buildString {
        append("@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> .\n")
        append("ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n")
        for (i in 0 until levels) append("ex:S$i sh:property [ sh:path ex:p ; sh:node ex:S${i + 1} ] .\n")
        append("ex:S$levels sh:class ex:Nope .\n")
        for (i in 0 until k) for (j in 0 until k) append("ex:a$i ex:p ex:b$j . ex:b$j ex:p ex:a$i .\n")
        for (j in 0 until k) append("ex:a ex:p ex:b$j .\n")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val graph = Rdf.parse(turtle(args.getOrNull(0)?.toInt() ?: 4, args.getOrNull(1)?.toInt() ?: 100), "TURTLE")
        val start = System.nanoTime()
        val outcome = try {
            Rdf4jValidation().use { it.validate(graph, Iri("http://ex/a")) }.javaClass.simpleName
        } catch (e: EmbeddedShapesRefusedException) {
            "refused ${e.message}"
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        val (kind, rest) = outcome.substringBefore(' ') to outcome.substringAfter(' ', "")
        println("RESULT $kind ms=$millis $rest")
    }
}
