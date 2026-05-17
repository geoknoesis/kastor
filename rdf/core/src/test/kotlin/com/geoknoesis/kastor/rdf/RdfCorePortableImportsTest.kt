package com.geoknoesis.kastor.rdf

import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the architectural rule that [`:rdf:core`](https://github.com/geoknoesis/kastor/blob/main/docs/kastor/concepts/architecture.md)
 * main sources stay free of Jena and RDF4J types (portable API layer).
 */
class RdfCorePortableImportsTest {

    @Test
    fun mainSourcesMustNotImportJenaOrRdf4j() {
        val prop =
            System.getProperty("kastor.core.main.sources")
                ?: error(
                    "Missing system property kastor.core.main.sources; " +
                        "the Gradle test task for :rdf:core sets this.",
                )
        val root = Path(prop)
        assertTrue(root.toFile().isDirectory, "Expected main Kotlin directory: $root")

        val violations =
            buildList {
                root.walk().filter { it.isRegularFile() && it.toString().endsWith(".kt") }.forEach { path ->
                    path.readLines().forEachIndexed { idx, line ->
                        val t = line.trim()
                        if (
                            t.startsWith("import org.apache.jena.") ||
                            t.startsWith("import org.eclipse.rdf4j.")
                        ) {
                            add("${path.fileName}:${idx + 1}: $t")
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Portable core must not import Jena or RDF4J in main sources:\n" +
                violations.joinToString("\n"),
        )
    }
}
