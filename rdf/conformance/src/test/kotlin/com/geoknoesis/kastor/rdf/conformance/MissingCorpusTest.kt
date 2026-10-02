package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The full-corpus suite ([Rdf12ConformanceRunner.forRoot]) must not go green without running: a corpus that is not
 * there **fails** the suite, and is only skipped when the caller opted out explicitly.
 *
 * Tagged **`conformance-smoke`**: it needs no W3C data.
 */
@Tag("conformance-smoke")
class MissingCorpusTest {
    @TempDir
    lateinit var tmp: Path

    private val conformer = Conformer(label = "Jena", provider = JenaProvider(), newDatasetRepo = { JenaRepository.MemoryRepository() })

    /** Runs the single test a missing corpus is reported as, and returns what it threw. */
    private fun outcomeOf(nodes: List<DynamicNode>): Throwable {
        val test = nodes.single() as DynamicTest
        return assertFailsWith<Throwable>("the placeholder test must not pass") { test.executable.execute() }
    }

    /** The ways a corpus can be missing, each as a root directory. */
    private fun missingCorpora(): Map<String, Path> {
        val noManifest = Files.createDirectories(tmp.resolve("no-manifest/rdf"))
        val emptyManifest = Files.createDirectories(tmp.resolve("empty-manifest/rdf"))
        Files.createDirectories(emptyManifest.resolve("rdf12"))
        Files.writeString(
            emptyManifest.resolve("rdf12/manifest.ttl"),
            "@prefix mf: <http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#> .\n<> a mf:Manifest .\n",
        )
        return mapOf(
            "no directory" to tmp.resolve("absent/rdf"),
            "no manifest" to noManifest,
            "a manifest without tests" to emptyManifest,
        )
    }

    @Test
    fun `a missing corpus fails the suite`() {
        for ((what, root) in missingCorpora()) {
            val outcome = outcomeOf(Rdf12ConformanceRunner.forRoot(conformer, root, allowMissingData = false))
            assertTrue(outcome is AssertionError, "$what: must fail, not skip ($outcome)")
            assertTrue(outcome !is TestAbortedException, what)
            val message = outcome.message.orEmpty()
            assertTrue("did not run" in message, "$what: $message")
            assertTrue("fetch-conformance-data.py" in message, "$what: says how to get the corpus: $message")
            assertTrue("-PconformanceAllowMissingData=true" in message, "$what: names the opt-out: $message")
        }
    }

    @Test
    fun `a missing corpus is skipped only on the explicit opt-out`() {
        for ((what, root) in missingCorpora()) {
            val outcome = outcomeOf(Rdf12ConformanceRunner.forRoot(conformer, root, allowMissingData = true))
            assertTrue(outcome is TestAbortedException, "$what: skipped on the opt-out ($outcome)")
        }
    }

    @Test
    fun `the opt-out is off unless the system property says true`() {
        val property = Rdf12ConformanceRunner.ALLOW_MISSING_DATA_PROPERTY
        assertEquals("conformance.allowMissingData", property)
        val before = System.getProperty(property)
        try {
            for ((value, skipped) in listOf(null to false, "false" to false, "" to false, "true" to true)) {
                if (value == null) System.clearProperty(property) else System.setProperty(property, value)
                val outcome = outcomeOf(Rdf12ConformanceRunner.forRoot(conformer, tmp.resolve("absent/rdf")))
                assertEquals(skipped, outcome is TestAbortedException, "$property=$value: $outcome")
            }
        } finally {
            if (before == null) System.clearProperty(property) else System.setProperty(property, before)
        }
    }

    @Test
    fun `a corpus that is there is run`() {
        val fixture = Path.of(checkNotNull(javaClass.classLoader.getResource("fixture/rdf12/rdf-turtle/manifest.ttl")).toURI())
        val root = Files.createDirectories(tmp.resolve("present/rdf"))
        Files.createDirectories(root.resolve("rdf12"))
        Files.list(fixture.parent).use { files -> files.forEach { Files.copy(it, root.resolve("rdf12").resolve(it.fileName.toString())) } }
        val container = Rdf12ConformanceRunner.forRoot(conformer, root, allowMissingData = false).single()
        assertTrue(container is DynamicContainer, "$container")
        assertTrue(container.children.count() > 0)
    }
}
