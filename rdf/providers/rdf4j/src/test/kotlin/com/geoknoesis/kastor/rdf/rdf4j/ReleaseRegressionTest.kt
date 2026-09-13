package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReleaseRegressionTest {
    @Test fun `inferred reads do not pretend to delete an unasserted statement`() {
        Rdf4jRepository.MemoryRdfsRepository().use { repo ->
            repo.editDefaultGraph().addTriples(listOf(
                RdfTriple(Iri("urn:A"), Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf"), Iri("urn:B")),
                RdfTriple(Iri("urn:i"), Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), Iri("urn:A"))
            ))
            val inferred = RdfTriple(Iri("urn:i"), Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), Iri("urn:B"))
            assertTrue(repo.defaultGraph.hasTriple(inferred))
            assertFalse(repo.editDefaultGraph().removeTriple(inferred))
            assertTrue(repo.defaultGraph.hasTriple(inferred))
        }
    }
    @Test fun `default graph mutations preserve named graphs and read transactions reject writes`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val t = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("v"))
            val name = Iri("urn:g")
            repo.editDefaultGraph().addTriple(t)
            repo.editGraph(name).addTriple(t)
            assertEquals(1, repo.defaultGraph.size())
            assertFailsWith<IllegalStateException> { repo.readTransaction { editDefaultGraph().clear() } }
            assertFailsWith<IllegalStateException> { repo.transaction { clear(); error("rollback") } }
            assertTrue(repo.defaultGraph.hasTriple(t))
            assertTrue(repo.getGraph(name).hasTriple(t))
            repo.editDefaultGraph().clear()
            assertTrue(repo.getGraph(name).hasTriple(t))
            assertFalse(repo.editDefaultGraph().removeTriple(t))
        }
    }
}
