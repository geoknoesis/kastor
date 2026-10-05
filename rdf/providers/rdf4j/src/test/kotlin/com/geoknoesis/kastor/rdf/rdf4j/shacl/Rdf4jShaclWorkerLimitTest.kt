package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ShaclSail`'s commit cannot be interrupted, so a validation that is given up on keeps its worker (and the copy of
 * the data it holds) until the commit returns. Such workers count against a limit, like the RDF4J reasoner's.
 *
 * Every interleaving is fixed by latches; the worker is held inside the `sailPhaseStarted` seam until the test lets
 * it go, and the only timeouts are long ones that turn a deadlock into a failure.
 */
class Rdf4jShaclWorkerLimitTest {
  private fun load(resource: String): RdfGraph =
      (Rdf4jShaclWorkerLimitTest::class.java.getResource(resource) ?: error("missing $resource"))
          .openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }

  private val graph = load("/shacl/rdf4j-mincount-valid.ttl")

  /** Holds the first worker in the seam, uninterruptibly like the commit, until [release] is counted down. */
  private class Gate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val first = java.util.concurrent.atomic.AtomicBoolean(true)

    val seam: () -> Unit = {
      if (first.compareAndSet(true, false)) {
        entered.countDown()
        var interrupted = false
        while (true) {
          try {
            release.await()
            break
          } catch (_: InterruptedException) {
            interrupted = true
          }
        }
        if (interrupted) Thread.currentThread().interrupt()
      }
    }
  }

  @Test
  fun `an abandoned worker keeps its slot until it finishes and no second worker starts meanwhile`() {
    val workers = Semaphore(1)
    val gate = Gate()
    val validator = Rdf4jShaclValidator(ValidationConfig(timeout = Duration.ofSeconds(60)), workers, gate.seam)

    var failure: Throwable? = null
    val caller = Thread { failure = runCatching { validator.validate(graph, graph) }.exceptionOrNull() }
    caller.isDaemon = true
    caller.start()
    assertTrue(gate.entered.await(60, TimeUnit.SECONDS), "the worker is running")
    assertEquals(0, workers.availablePermits())

    // The caller gives up; the worker, stuck in its uninterruptible commit, is only asked to stop.
    caller.interrupt()
    caller.join(TimeUnit.SECONDS.toMillis(60))
    assertTrue(!caller.isAlive, "the caller returned")
    assertTrue(failure is ShaclValidationException, failure.toString())
    assertEquals(0, workers.availablePermits(), "the abandoned worker still holds its slot")

    // A new validation finds every slot taken: it does not start a worker, and fails when its own deadline is up.
    val rejected = assertFailsWith<ShaclValidationException> {
      Rdf4jShaclValidator(ValidationConfig(timeout = Duration.ofNanos(1)), workers).validate(graph, graph)
    }
    assertTrue("too many validations" in rejected.message.orEmpty(), rejected.message)

    gate.release.countDown()
    assertTrue(workers.tryAcquire(60, TimeUnit.SECONDS), "the abandoned worker returns its slot when it finishes")
    workers.release()
    assertTrue(Rdf4jShaclValidator(ValidationConfig(), workers).validate(graph, graph).isValid)
    assertEquals(1, workers.availablePermits())
  }
}
