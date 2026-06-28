package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Rdf4jRepositoryTest {
  // Previously @Disabled("RDF4J connection management needs investigation"): the old
  // single-shared-connection model could not reliably add-then-read. Now fixed by the
  // per-operation connection model.
  @Test
  fun `rdf4j memory basic ops`() {
    Rdf4jRepository.MemoryRepository().use { repo ->
      val s = Iri("urn:ex:s")
      val p = Iri("urn:ex:p")
      val o = Literal("hi")

      repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))

      val result = repo.select(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }"))
      assertEquals(1, result.count())
      assertEquals(s, result.first()?.get("s"))

      val g = repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"))
      assertEquals(1, g.count())

      val ask = repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))
      assertTrue(ask)
    }
  }

  @Test
  fun `transaction commits atomically and is visible afterwards`() {
    Rdf4jRepository.MemoryRepository().use { repo ->
      val p = Iri("urn:ex:p")
      repo.transaction {
        val g = editDefaultGraph()
        repeat(5) { i -> g.addTriple(RdfTriple(Iri("urn:ex:s$i"), p, Literal("v$i"))) }
      }
      assertEquals(5, repo.defaultGraph.size())
    }
  }

  @Test
  fun `transaction rolls back on failure`() {
    Rdf4jRepository.MemoryRepository().use { repo ->
      val p = Iri("urn:ex:p")
      runCatching {
        repo.transaction {
          editDefaultGraph().addTriple(RdfTriple(Iri("urn:ex:s"), p, Literal("v")))
          throw IllegalStateException("boom")
        }
      }
      // The transaction rolled back, so the partial write must not be visible.
      assertEquals(0, repo.defaultGraph.size())
    }
  }

  // Concurrent reads and writes from many threads must not corrupt state or throw —
  // something the old single shared (non-thread-safe) RepositoryConnection could not
  // guarantee. With per-operation connections this is safe.
  @Test
  fun `concurrent add and select are thread-safe`() {
    Rdf4jRepository.MemoryRepository().use { repo ->
      val p = Iri("urn:ex:p")
      val threads = 8
      val perThread = 50
      val pool = Executors.newFixedThreadPool(threads)
      val start = CountDownLatch(1)
      val errors = ConcurrentLinkedQueue<Throwable>()
      try {
        val futures = (0 until threads).map { t ->
          pool.submit {
            start.await()
            try {
              repeat(perThread) { i ->
                repo.editDefaultGraph().addTriple(RdfTriple(Iri("urn:ex:s-$t-$i"), p, Literal("v")))
                // Interleave reads with concurrent writes from other threads.
                repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))
              }
            } catch (e: Throwable) {
              errors.add(e)
            }
          }
        }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
      } finally {
        pool.shutdownNow()
      }
      assertTrue(errors.isEmpty()) { "Concurrent access threw: ${errors.joinToString { it.toString() }}" }
      assertEquals(threads * perThread, repo.defaultGraph.size())
    }
  }
}
