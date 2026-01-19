package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JenaRepositoryAdditionalTests {
  @Test
  fun `transaction rollback discards changes`() {
    // Use TDB2 repository which supports transactions
    val tempDir = java.nio.file.Files.createTempDirectory("jena-transaction-test")
    tempDir.toFile().deleteOnExit()
    
    val repo = JenaRepository.Tdb2Repository(tempDir.toString())
    val s = Iri("urn:t:s")
    val p = Iri("urn:t:p")
    val o = Literal("v")

    assertThrows(Exception::class.java) {
      repo.transaction {
        defaultGraph.addTriple(RdfTriple(s, p, o))
        // Rollback is automatic on exception
        throw RuntimeException("Rollback")
      }
    }

    var askResult = false
    repo.readTransaction {
      askResult = ask(SparqlAskQuery("ASK { <urn:t:s> <urn:t:p> ?o }"))
    }
    assertFalse(askResult)
  }

  @Test
  fun `named graph insert and query`() {
    val repo = JenaRepository.MemoryRepository()

    val g = Iri("urn:g")
    val s = Iri("urn:g:s")
    val p = Iri("urn:g:p")
    val o = Literal("x")

    repo.getGraph(g).addTriple(RdfTriple(s, p, o))

    val result = repo.select(SparqlSelectQuery("SELECT ?s WHERE { GRAPH <urn:g> { ?s <urn:g:p> ?o } }"))
    assertEquals(1, result.count())
    assertEquals(s, result.first()?.get("s"))
  }

  @Test
  fun `update insert data works`() {
    val repo = JenaRepository.MemoryRepository()
    repo.update(UpdateQuery("INSERT DATA { <urn:u:s> <urn:u:p> 'ok' }"))
    assertTrue(repo.ask(SparqlAskQuery("ASK { <urn:u:s> <urn:u:p> 'ok' }")))
  }
}










