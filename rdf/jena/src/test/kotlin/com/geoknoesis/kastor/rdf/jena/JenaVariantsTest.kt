package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JenaVariantsTest {
  @Test
  @org.junit.jupiter.api.Disabled("TDB2 persistence needs investigation")
  fun `jena tdb2 persists data at location`() {
    val dir = Files.createTempDirectory("jena-tdb2-test").toFile()
    dir.deleteOnExit()

    // Write session
    val repo1 = JenaRepository.Tdb2Repository(dir.absolutePath)
    val s = iri("urn:tdb2:s")
    val p = iri("urn:tdb2:p")
    val o = literal("persist")
    repo1.defaultGraph.addTriple(RdfTriple(s, p, o))

    // New session against same location should see data
    val repo2 = JenaRepository.Tdb2Repository(dir.absolutePath)
    val ask = repo2.ask("ASK { <urn:tdb2:s> <urn:tdb2:p> 'persist' }")
    assertTrue(ask)
  }
}

