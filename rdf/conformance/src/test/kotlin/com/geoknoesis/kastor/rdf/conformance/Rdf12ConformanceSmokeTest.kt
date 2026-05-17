package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path

/**
 * Smoke test that runs the conformance harness against a tiny synthetic
 * fixture under `src/test/resources/fixture/`, independent of the W3C
 * submodule.
 *
 * The fixture covers one positive-syntax, one negative-syntax, and one eval
 * test. It exists for two reasons:
 *
 * 1. CI without the submodule still gains coverage of the harness code paths
 *    (manifest parsing, dynamic test generation, isomorphism comparison).
 * 2. Local developers see immediate pass/fail signal as they work on the
 *    runner without needing to clone ~hundreds of MB of W3C test data.
 *
 * The real W3C suite is run by [JenaConformanceTest]/[Rdf4jConformanceTest]
 * once the submodule is checked out.
 *
 * Tagged **`conformance-smoke`** — invoked by the Gradle task **`conformanceSmokeTest`**
 * on `:rdf:conformance` (and PR CI) without checking out the submodule.
 */
@Tag("conformance-smoke")
class Rdf12ConformanceSmokeTest {

    private val fixtureRoot: Path = run {
        val url = checkNotNull(javaClass.classLoader.getResource("fixture/rdf12/rdf-turtle/manifest.ttl")) {
            "fixture manifest not on the test classpath"
        }
        Path.of(url.toURI())
    }

    @TestFactory
    fun `smoke fixture (Jena)`(): List<DynamicNode> {
        val conformer = Conformer(
            label = "Jena",
            provider = JenaProvider(),
            newDatasetRepo = { JenaRepository.MemoryRepository() },
        )
        return listOf(
            Rdf12ConformanceRunner.forManifest(conformer, "fixture", fixtureRoot)
        )
    }

    @TestFactory
    fun `smoke fixture (RDF4J)`(): List<DynamicNode> {
        val conformer = Conformer(
            label = "RDF4J",
            provider = Rdf4jProvider(),
            newDatasetRepo = { Rdf4jRepository.MemoryRepository() },
        )
        return listOf(
            Rdf12ConformanceRunner.forManifest(conformer, "fixture", fixtureRoot)
        )
    }
}
