package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A streamed `CONSTRUCT` de-duplicates the `rdf:reifies` triples it synthesizes with a bounded memory: it remembers
 * the most recently seen ones only, so a result of any size is streamed in constant memory. The materialized
 * `construct` (which holds the whole result anyway) de-duplicates exactly.
 */
class Rdf4jConstructStreamWindowTest {
    private val ex = "http://example.org/"

    /** Annotations ordered by their object: three different quoted triples, then the first one again. */
    private val data =
        "PREFIX ex: <$ex> INSERT DATA { " +
            "<< ex:s ex:p 1 >> ex:q \"1\" . << ex:s ex:p 2 >> ex:q \"2\" . << ex:s ex:p 3 >> ex:q \"3\" . << ex:s ex:p 1 >> ex:q \"4\" }"
    private val query = SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } ORDER BY ?o")

    private fun Rdf4jRepository.streamedReifies(): Int = withConstructTriples(query) { triples -> triples.count { it.predicate == RDF.reifies } }

    @Test
    fun `a streamed CONSTRUCT returns each rdf-reifies triple once within its window`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery(data))
            assertEquals(3, repo.streamedReifies(), "the default window holds all three")
            assertEquals(4, repo.withConstructTriples(query) { it.count { triple -> triple.predicate != RDF.reifies } })
        }
    }

    @Test
    fun `a streamed CONSTRUCT forgets the rdf-reifies triples beyond its window`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery(data))
            repo.constructReifiesWindow = 2
            // The first quoted triple left the window of two when the third arrived: its triple is returned again.
            assertEquals(4, repo.streamedReifies())
            repo.constructReifiesWindow = 3
            assertEquals(3, repo.streamedReifies())
            // Seeing a triple again keeps it in the window (least recently seen leaves first).
            repo.update(UpdateQuery("PREFIX ex: <$ex> INSERT DATA { << ex:s ex:p 1 >> ex:q \"25\" }"))
            repo.constructReifiesWindow = 2
            // Order by object: "1", "2", "25" (the first triple again: it stays), "3" (the second one leaves), "4".
            assertEquals(3, repo.streamedReifies())
        }
    }

    @Test
    fun `the materialized construct de-duplicates exactly whatever the window`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery(data))
            repo.constructReifiesWindow = 1
            assertEquals(3, repo.construct(query).count { it.predicate == RDF.reifies })
        }
    }
}
