package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JenaBasicsExample {
  @Test
  fun basics() {
    val repo = JenaRepository.MemoryRepository()

    val s = Iri("urn:ex:s")
    val p = Iri("urn:ex:p")
    val o = Literal("hello")

    // Add triple using the new API
    repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))

    // Simple SELECT
    val result = repo.select(SparqlSelectQuery("SELECT ?s WHERE { ?s <urn:ex:p> ?o }"))
    assertEquals(1, result.count())
    assertEquals(s, result.first()?.get("s"))

    // CONSTRUCT and ASK
    val g = repo.construct(SparqlConstructQuery("CONSTRUCT { ?s <urn:ex:p> ?o } WHERE { ?s <urn:ex:p> ?o }"))
    assertEquals(1, g.count())
    val ask = repo.ask(SparqlAskQuery("ASK { ?s <urn:ex:p> ?o }"))
    assertEquals(true, ask)
  }
}









