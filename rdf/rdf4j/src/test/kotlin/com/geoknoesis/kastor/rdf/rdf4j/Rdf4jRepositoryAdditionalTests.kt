package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jRepositoryAdditionalTests {
  @Test
  @org.junit.jupiter.api.Disabled("RDF4J transaction management needs investigation")
  fun `transaction rollback discards changes`() {
    val repo = Rdf4jRepository.MemoryRepository()

    val s = iri("urn:t:s")
    val p = iri("urn:t:p")
    val o = literal("v")

    repo.transaction {
      defaultGraph.addTriple(RdfTriple(s, p, o))
      // Rollback is automatic on exception
      throw RuntimeException("Rollback")
    }

    val ask = repo.ask("ASK { <urn:t:s> <urn:t:p> ?o }")
    assertFalse(ask)
  }

  @Test
  @org.junit.jupiter.api.Disabled("RDF4J named graph management needs investigation")
  fun `named graph insert and query`() {
    val repo = Rdf4jRepository.MemoryRepository()

    val g = iri("urn:g")
    val s = iri("urn:g:s")
    val p = iri("urn:g:p")
    val o = literal("x")

    repo.getGraph(g).addTriple(RdfTriple(s, p, o))

    val result = repo.query("SELECT ?s WHERE { GRAPH <urn:g> { ?s <urn:g:p> ?o } }")
    assertEquals(1, result.count())
    assertEquals(s, result.first()?.get("s"))
  }

  @Test
  fun `update insert data works`() {
    val repo = Rdf4jRepository.MemoryRepository()
    repo.update("INSERT DATA { <urn:u:s> <urn:u:p> 'ok' }")
    assertTrue(repo.ask("ASK { <urn:u:s> <urn:u:p> 'ok' }"))
  }
}

