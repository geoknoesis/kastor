package com.geoknoesis.kastor.rdf.conformance

import java.nio.file.Path

/**
 * Resolves the on-disk root of the W3C `rdf-tests` submodule.
 *
 * The submodule lives at `<repo-root>/rdf/conformance/test-data/`. The runner
 * is invoked from Gradle, where the working directory is the module root
 * (`<repo-root>/rdf/conformance/`), so we resolve `test-data/` relative to it.
 *
 * Inside the upstream repo the RDF-spec test trees live under `rdf/rdf11/`,
 * `rdf/rdf12/`, ... so we expose the parent `rdf/` directory as the root the
 * runner walks; the runner itself looks for a `rdf12` child below it.
 */
object TestData {
    val rootDir: Path = Path.of("test-data", "rdf").toAbsolutePath().normalize()
}
