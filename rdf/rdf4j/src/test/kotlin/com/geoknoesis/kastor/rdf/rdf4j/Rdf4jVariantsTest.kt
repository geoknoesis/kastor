package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Rdf4jVariantsTest {
  @Test
  fun `rdf4j memory works`() {
    val repo = Rdf4jRepository.MemoryRepository()
    val s = iri("urn:memory:s")
    val p = iri("urn:memory:p")
    val o = literal("test")
    repo.defaultGraph.addTriple(RdfTriple(s, p, o))
    assertTrue(repo.ask("ASK { <urn:memory:s> <urn:memory:p> 'test' }"))
  }

  @Test
  @org.junit.jupiter.api.Disabled("RDF4J native persistence needs investigation")
  fun `rdf4j native persists data at location`() {
    val dir = Files.createTempDirectory("rdf4j-native-test").toFile()
    dir.deleteOnExit()

    // Write session
    val repo1 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val s = iri("urn:native:s")
    val p = iri("urn:native:p")
    val o = literal("persist")
    repo1.defaultGraph.addTriple(RdfTriple(s, p, o))

    // New session against same location should see data
    val repo2 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val ask = repo2.ask("ASK { <urn:native:s> <urn:native:p> 'persist' }")
    assertTrue(ask)
  }
}

