package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JenaBasicsExample {
  @Test
  fun basics() {
    val repo = JenaRepository.MemoryRepository()

    val s = iri("urn:ex:s")
    val p = iri("urn:ex:p")
    val o = literal("hello")

    // Add triple using the new API
    repo.defaultGraph.addTriple(RdfTriple(s, p, o))

    // Simple SELECT
    val result = repo.query("SELECT ?s WHERE { ?s <urn:ex:p> ?o }")
    assertEquals(1, result.count())
    assertEquals(s, result.first().get("s"))

    // CONSTRUCT and ASK
    val g = repo.construct("CONSTRUCT { ?s <urn:ex:p> ?o } WHERE { ?s <urn:ex:p> ?o }")
    assertEquals(1, g.size)
    val ask = repo.ask("ASK { ?s <urn:ex:p> ?o }")
    assertEquals(true, ask)
  }
}
