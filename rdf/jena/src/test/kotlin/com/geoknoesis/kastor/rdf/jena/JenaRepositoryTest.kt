package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenaRepositoryTest {
  @Test
  fun `jena memory basic ops`() {
    val repo = JenaRepository.MemoryRepository()

    val s = Iri("urn:ex:s")
    val p = Iri("urn:ex:p")
    val o = Literal("hi")

    repo.defaultGraph.addTriple(RdfTriple(s, p, o))

    val result = repo.select(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }"))
    assertEquals(1, result.count())
    assertEquals(s, result.first()?.get("s"))

    val g = repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"))
    assertEquals(1, g.count())

    val ask = repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))
    assertTrue(ask)
  }
}










