package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Rdf4jAtomicGraphTest {
    @Test
    fun `default and named compound edits roll back and expose source identity`() {
        val native = SailRepository(MemoryStore()).also { it.init() }
        Rdf4jRepository(native).use { repo ->
            val old = RdfTriple(BlankNode("b0"), Iri("urn:p"), string("old"))
            listOf(repo.editDefaultGraph(), repo.editGraph(Iri("urn:g"))).forEach { graph ->
                graph.addTriple(old)
                assertThrows(IllegalStateException::class.java) {
                    (graph as AtomicMutableRdfGraph).mutateAtomically {
                        removeTriple(old)
                        addTriple(old.copy(obj = string("new")))
                        error("abort")
                    }
                }
                assertEquals(listOf(old), graph.getTriples())
                assertSame(repo, (graph as SourceTrackedGraph).sourceRepository)
            }
        }
    }
}
