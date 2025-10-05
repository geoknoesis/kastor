package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenaRepositoryTest {
  @Test
  fun `jena memory basic ops`() {
    val repo = JenaRepository.MemoryRepository()

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

