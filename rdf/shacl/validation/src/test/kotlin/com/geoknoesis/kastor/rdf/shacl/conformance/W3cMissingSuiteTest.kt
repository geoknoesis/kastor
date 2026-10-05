package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException

/** A W3C suite that is missing or empty must fail the conformance test, never pass having run nothing. */
class W3cMissingSuiteTest {

    @TempDir
    lateinit var dir: Path

    private fun onlyTest(manifest: Path, allowMissing: Boolean): DynamicTest {
        val nodes = Shacl12NativeConformanceTest().suite(manifest, allowMissing).toList()
        assertEquals(1, nodes.size)
        return nodes.single() as DynamicTest
    }

    private fun emptyManifest(): Path =
        dir.resolve("manifest.ttl").also {
            Files.writeString(it, "@prefix mf: <http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#> .\n<> a mf:Manifest .\n")
        }

    @Test
    fun `a missing manifest fails unless the suite is opted out`() {
        val missing = dir.resolve("absent.ttl")
        assertThrows(AssertionFailedError::class.java) { onlyTest(missing, allowMissing = false).executable.execute() }
        assertThrows(TestAbortedException::class.java) { onlyTest(missing, allowMissing = true).executable.execute() }
    }

    @Test
    fun `a manifest without validate entries fails unless the suite is opted out`() {
        val manifest = emptyManifest()
        assertThrows(AssertionFailedError::class.java) { onlyTest(manifest, allowMissing = false).executable.execute() }
        assertThrows(TestAbortedException::class.java) { onlyTest(manifest, allowMissing = true).executable.execute() }
    }
}
