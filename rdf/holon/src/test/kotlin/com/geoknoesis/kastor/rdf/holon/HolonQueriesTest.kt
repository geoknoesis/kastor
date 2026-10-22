package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDFH
import org.junit.jupiter.api.Test
import kotlin.test.*

class HolonQueriesTest {
  private val a = ex("A")
  private val b = ex("B")

  @Test fun `query graph supports cross-holon joins and closes only its own repository`() {
    val shared = BlankNode("shared")
    val context = HolonContext.of(HolonGraph(registrations(a, b) + t(b, RDFH.parentHolon, a)),
      mapOf(a to g(t(a, ex("link"), shared)), b to g(t(shared, ex("value"), string("found")))))
    val result = context.resolve(a, HolonScope.AGGREGATED)
    result.asDataset().use { dataset ->
      assertTrue(dataset.ask(SparqlAskQuery("ASK { <${a.value}> <${ex("link").value}> ?shared . ?shared <${ex("value").value}> \"found\" }")))
      assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p ?o } }")))
      assertEquals(0, dataset.namedGraphs.size)
    }
    assertEquals(2, result.graph.size())
  }

  @Test fun `attribution preserves duplicate ownership while default graph is a set`() {
    val triple = t(a, ex("value"), string("one"))
    val result = HolonContext.of(HolonGraph(registrations(a, b) + t(b, RDFH.parentHolon, a)),
      mapOf(a to g(triple), b to g(triple))).resolve(a, HolonScope.AGGREGATED)
    result.asDataset(attributed = true).use { dataset ->
      assertEquals(setOf(a, b), dataset.namedGraphs.keys)
      val count = dataset.select(SparqlSelectQuery("SELECT (COUNT(*) AS ?count) WHERE { GRAPH ?g { ?s ?p ?o } }")).first()
      assertEquals(2, count?.getInt("count"))
      val union = dataset.select(SparqlSelectQuery("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }")).first()
      assertEquals(1, union?.getInt("count"))
    }
  }

  @Test fun `empty attribution reports the query provider limitation explicitly`() {
    val result = HolonContext.of(HolonGraph(registrations(a))).resolve(a)
    assertEquals(setOf(a), result.attributedGraphs.keys)
    error(HolonError.UNSUPPORTED_CAPABILITY) { result.asDataset(attributed = true) }
  }

  @Test fun `blank-node attribution is retained natively and query limitation is explicit`() {
    val h = BlankNode("h")
    val result = HolonContext.of(g(t(h, RDFH.contentGraph, a))).resolve(h)
    assertEquals(setOf(h), result.attributedGraphs.keys)
    error(HolonError.UNSUPPORTED_CAPABILITY) { result.asDataset(attributed = true) }
    result.asDataset().use { assertFalse(it.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))) }
  }

  @Test fun `query datasets reject caller dataset clauses instead of escaping scope`() {
    val result = HolonContext.of(HolonGraph(registrations(a))).resolve(a)
    result.asDataset().use { dataset ->
      assertFailsWith<IllegalArgumentException> { dataset.ask(SparqlAskQuery("ASK FROM <https://example.org/elsewhere> { ?s ?p ?o }")) }
    }
  }

  @Test fun `global is a validation scope rather than a query dataset`() {
    val result = HolonContext.of(HolonGraph(registrations(a))).resolve(a, HolonScope.GLOBAL)
    error(HolonError.UNSUPPORTED_CAPABILITY) { result.asDataset() }
  }

  @Test fun `query repository is closed when materialization fails`() {
    val repository = Rdf.repository { providerId = "memory" }
    val failing = object : RdfRepository by repository {
      override fun transaction(operations: RdfRepository.() -> Unit) {
        throw IllegalStateException("Cannot write query snapshot")
      }
    }
    val result = HolonContext.of(HolonGraph(registrations(a))).resolve(a)
    assertFails { result.asDataset(repositoryFactory = { failing }) }
    assertTrue(repository.isClosed())
  }
}
