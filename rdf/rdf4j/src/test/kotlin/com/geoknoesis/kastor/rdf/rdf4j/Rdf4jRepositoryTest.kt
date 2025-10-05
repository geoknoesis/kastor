package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jRepositoryTest {
  @Test
  @org.junit.jupiter.api.Disabled("RDF4J connection management needs investigation")
  fun `rdf4j memory basic ops`() {
    val repo = Rdf4jRepository.MemoryRepository()

    val s = iri("urn:ex:s")
    val p = iri("urn:ex:p")
    val o = literal("hi")

    repo.defaultGraph.addTriple(RdfTriple(s, p, o))

    val result = repo.query("SELECT ?s WHERE { ?s ?p ?o }")
    assertEquals(1, result.count())
    assertEquals(s, result.first().get("s"))

    val g = repo.construct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
    assertEquals(1, g.size)

    val ask = repo.ask("ASK { ?s ?p ?o }")
    assertTrue(ask)
  }
}

