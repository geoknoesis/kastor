package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The validation cache serves a cached graph state when the handle and its modification stamp are equal, without
 * reading the graph again. That is only correct if the stamp identifies the content the CALLING THREAD would read:
 * a transaction must see its own uncommitted writes (never a state another thread cached from committed content),
 * and other threads must never be served a state cached from a transaction's uncommitted content.
 *
 * Real repositories, real cache, two threads sequenced with latches.
 */
class TransactionIsolationCacheTest {
    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)
    private val graphName = ex("people")
    private val nameOfA = RdfTriple(ex("a"), ex("name"), Literal("a"))

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] .
    """.trimIndent()

    private fun people(vararg names: String): List<RdfTriple> = names.flatMap { p ->
        listOf(RdfTriple(ex(p), RDF.type, ex("Person")), RdfTriple(ex(p), ex("name"), Literal(p)))
    }

    private class Rollback : RuntimeException("roll back")

    private val repositories: List<Pair<String, () -> RdfRepository>> = listOf(
        "jena memory" to { JenaRepository.MemoryRepository() },
    )

    @TestFactory
    fun `a transaction sees its own writes and other threads never see them`(): List<DynamicTest> =
        repositories.flatMap { (label, create) ->
            listOf(true, false).map { commit ->
                DynamicTest.dynamicTest("$label, ${if (commit) "commit" else "rollback"}") { scenario(create(), commit) }
            }
        }

    private fun scenario(repo: RdfRepository, commit: Boolean) {
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "isolation-test").apply { isDaemon = true } }
        try {
            repo.editGraph(graphName).addTriples(people("a", "b"))
            JenaValidation.fromTurtle(shapes).use { v ->
                // A committed state is cached before the transaction starts.
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(graphName), ex("a")))

                val wrote = CountDownLatch(1)
                val otherDone = CountDownLatch(1)
                val writer = pool.submit<ValidationResult> {
                    var inside: ValidationResult? = null
                    try {
                        repo.transaction {
                            editGraph(graphName).removeTriple(nameOfA)
                            // The transaction's own view lacks the name: a cached committed state must not be served.
                            inside = v.validate(repo.getGraph(graphName), ex("a"))
                            wrote.countDown()
                            // Keep the transaction open while the other thread validates. A store that makes readers
                            // wait for the writer simply lets this time out; the assertions below hold either way.
                            otherDone.await(5, TimeUnit.SECONDS)
                            if (!commit) throw Rollback()
                        }
                    } catch (_: Rollback) {
                        // rolled back on purpose
                    }
                    inside!!
                }
                assertTrue(wrote.await(60, TimeUnit.SECONDS), "the writer did not reach its validation")
                val other = pool.submit<ValidationResult> {
                    try {
                        // Still the committed content for this thread, although the writer cached its own view.
                        v.validate(repo.getGraph(graphName), ex("a"))
                    } finally {
                        otherDone.countDown()
                    }
                }

                assertTrue(writer.get(60, TimeUnit.SECONDS) is ValidationResult.Violations, "the writer must see its own uncommitted removal")
                val seenByOther = other.get(60, TimeUnit.SECONDS)
                if (commit) {
                    // The other thread read either before the commit (Ok) or, where readers wait for the writer,
                    // after it (Violations): both are committed states. After the commit everyone sees the removal.
                    assertTrue(v.validate(repo.getGraph(graphName), ex("a")) is ValidationResult.Violations, "committed removal is visible")
                } else {
                    assertEquals(ValidationResult.Ok, seenByOther, "another thread must never see uncommitted content")
                    assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(graphName), ex("a")), "a rolled-back removal is not visible")
                }
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(graphName), ex("b")))
            }
        } finally {
            pool.shutdownNow()
            repo.close()
        }
    }

    @TestFactory
    fun `a writer still sees its own write after another thread validated in between`(): List<DynamicTest> =
        repositories.map { (label, create) -> DynamicTest.dynamicTest(label) { writerAfterOther(create()) } }

    private fun writerAfterOther(repo: RdfRepository) {
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "isolation-test").apply { isDaemon = true } }
        try {
            repo.editGraph(graphName).addTriples(people("a", "b"))
            JenaValidation.fromTurtle(shapes).use { v ->
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(graphName), ex("a")))

                val wrote = CountDownLatch(1)
                val otherDone = CountDownLatch(1)
                val writer = pool.submit<ValidationResult> {
                    var inside: ValidationResult? = null
                    repo.transaction {
                        editGraph(graphName).removeTriple(nameOfA)
                        wrote.countDown()
                        // The other thread validates the committed content now (and may cache it); where readers wait
                        // for the writer this times out instead.
                        otherDone.await(5, TimeUnit.SECONDS)
                        inside = v.validate(repo.getGraph(graphName), ex("a"))
                    }
                    inside!!
                }
                assertTrue(wrote.await(60, TimeUnit.SECONDS), "the writer did not write")
                val other = pool.submit<ValidationResult> {
                    try {
                        v.validate(repo.getGraph(graphName), ex("a"))
                    } finally {
                        otherDone.countDown()
                    }
                }
                assertTrue(writer.get(60, TimeUnit.SECONDS) is ValidationResult.Violations, "the writer must not be served the state another thread cached from committed content")
                other.get(60, TimeUnit.SECONDS)
                assertTrue(v.validate(repo.getGraph(graphName), ex("a")) is ValidationResult.Violations, "committed removal is visible")
            }
        } finally {
            pool.shutdownNow()
            repo.close()
        }
    }
}
