package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenaRepositoryAdditionalTests {
  @Test
  @org.junit.jupiter.api.Disabled("Transaction rollback needs investigation")
  fun `transaction rollback discards changes`() {
    // Use TDB2 repository which supports transactions
    val tempDir = java.nio.file.Files.createTempDirectory("jena-transaction-test")
    tempDir.toFile().deleteOnExit()
    
    val repo = JenaRepository.Tdb2Repository(tempDir.toString())
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
  fun `named graph insert and query`() {
    val repo = JenaRepository.MemoryRepository()

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
    val repo = JenaRepository.MemoryRepository()
    repo.update("INSERT DATA { <urn:u:s> <urn:u:p> 'ok' }")
    assertTrue(repo.ask("ASK { <urn:u:s> <urn:u:p> 'ok' }"))
  }
}

