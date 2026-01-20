package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Rdf4jVariantsTest {
  @Test
  fun `rdf4j memory works`() {
    val repo = Rdf4jRepository.MemoryRepository()
    val s = Iri("urn:memory:s")
    val p = Iri("urn:memory:p")
    val o = Literal("test")
    repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
    assertTrue(repo.ask(SparqlAskQuery("ASK { <urn:memory:s> <urn:memory:p> 'test' }")))
  }

  @Test
  @org.junit.jupiter.api.Disabled("RDF4J native persistence needs investigation")
  fun `rdf4j native persists data at location`() {
    val dir = Files.createTempDirectory("rdf4j-native-test").toFile()
    dir.deleteOnExit()

    // Write session
    val repo1 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val s = Iri("urn:native:s")
    val p = Iri("urn:native:p")
    val o = Literal("persist")
    repo1.editDefaultGraph().addTriple(RdfTriple(s, p, o))

    // New session against same location should see data
    val repo2 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val ask = repo2.ask(SparqlAskQuery("ASK { <urn:native:s> <urn:native:p> 'persist' }"))
    assertTrue(ask)
  }
}










