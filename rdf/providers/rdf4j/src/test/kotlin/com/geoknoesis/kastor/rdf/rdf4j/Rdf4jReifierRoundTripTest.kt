package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writing the RDF 1.2 reified view back, reserved reifier ids, lenient reads and the full statement conversion. */
class Rdf4jReifierRoundTripTest {
    private val ex = "http://example.org/"
    private val doc = """
        PREFIX ex: <$ex>
        ex:alice ex:name "Alice" .
        << ex:alice ex:age 30 >> ex:certainty "0.9" ; ex:source ex:census .
    """.trimIndent()

    private fun countAll(repo: Rdf4jRepository): Int =
        (repo.select(SparqlSelectQuery("SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }")).first()!!.get("n") as Literal).lexical.toInt()

    @Test
    fun `writing the reified view back into the RDF-star store adds no duplicates`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            Rdf4jProvider().parseDataset(repo, doc.byteInputStream(), "TURTLE")
            val graph = repo.editDefaultGraph()
            val view = graph.getTriples()
            assertEquals(4, view.size, "$view")
            val storedBefore = countAll(repo)

            graph.addTriples(view)
            // The view's rdf:reifies triple is now explicit: it is stored once (see Rdf4jGraph), the annotations are not.
            assertEquals(storedBefore + 1, countAll(repo), "SPARQL must not see duplicate statements")
            view.forEach { graph.addTriple(it) }

            assertEquals(storedBefore + 1, countAll(repo), "writing the view again changes nothing")
            assertEquals(view.toSet(), graph.getTriples().toSet())
            assertEquals(4, graph.size())
        }
    }

    @Test
    fun `a reified view copied into an empty RDF-star store reproduces the RDF-star statements`() {
        Rdf4jRepository.MemoryRepository().use { source ->
            Rdf4jProvider().parseDataset(source, doc.byteInputStream(), "TURTLE")
            val view = source.defaultGraph.getTriples()
            Rdf4jRepository.MemoryRepository().use { target ->
                // rdf:reifies first: it is stored until an annotation about the quoted triple implies it.
                target.editDefaultGraph().addTriples(view.sortedByDescending { it.predicate == RDF.reifies })
                assertEquals(view.toSet(), target.defaultGraph.getTriples().toSet())
                assertTrue(target.ask(com.geoknoesis.kastor.rdf.SparqlAskQuery("ASK { << <${ex}alice> <${ex}age> 30 >> <${ex}source> <${ex}census> }")))
                assertEquals(view.size, target.defaultGraph.size())
            }
        }
    }

    @Test
    fun `user blank nodes that look like reifiers are ordinary blank nodes`() {
        val p = Iri(ex + "p")
        for (id in listOf("kastor-star-foo", "kastor-star-0123456789abcdef0123456789abcdef", "kastor-star-VDI6-deadbeef")) {
            assertNull(Rdf4jTerms.quotedTripleOf(id), id)
            Rdf4jRepository.MemoryRepository().use { repo ->
                val graph = repo.editDefaultGraph()
                val triple = RdfTriple(BlankNode(id), p, Literal("v"))
                graph.addTriple(triple)
                assertEquals(listOf(triple), graph.find(BlankNode(id), null, null), id)
                assertTrue(graph.hasTriple(triple), id)
                assertEquals(1, graph.size(), id)
                assertEquals(1, countAll(repo), id)
                assertTrue(graph.removeTriple(triple), id)
                assertEquals(0, graph.size(), id)
            }
        }
    }

    @Test
    fun `reifier ids decode to their quoted triple and reject tampering`() {
        val vf = SimpleValueFactory.getInstance()
        val quoted = vf.createTriple(
            vf.createTriple(vf.createBNode("b 1"), vf.createIRI(ex + "p"), vf.createLiteral("x", "ar-EG--rtl")),
            vf.createIRI(ex + "q"),
            vf.createLiteral("42", vf.createIRI("http://www.w3.org/2001/XMLSchema#integer")),
        )
        val reifier = Rdf4jTerms.reifierFor(quoted)
        assertEquals(quoted, Rdf4jTerms.quotedTripleOf(reifier.id))
        assertEquals(reifier, Rdf4jTerms.reifierFor(quoted))
        val tampered = reifier.id.dropLast(1) + if (reifier.id.last() == '0') '1' else '0'
        assertNull(Rdf4jTerms.quotedTripleOf(tampered))
    }

    @Test
    fun `rdfTriplesFromRdf4j returns the rdf reifies triples that the single form drops`() {
        val vf = SimpleValueFactory.getInstance()
        val quoted = vf.createTriple(vf.createIRI(ex + "alice"), vf.createIRI(ex + "age"), vf.createLiteral("30"))
        val statement = vf.createStatement(quoted, vf.createIRI(ex + "certainty"), vf.createLiteral("0.9"))
        val triples = rdfTriplesFromRdf4j(statement)
        assertEquals(2, triples.size)
        val reifier = triples[0].subject
        assertEquals(RdfTriple(reifier, RDF.reifies, TripleTerm(RdfTriple(Iri(ex + "alice"), Iri(ex + "age"), Literal("30")))), triples[1])
        @Suppress("DEPRECATION")
        assertEquals(triples[0], rdfTripleFromRdf4j(statement))
    }

    @Test
    fun `lenient reads skip statements Kastor cannot represent and size agrees`() {
        fun wrapped(lenient: Boolean): Rdf4jRepository {
            val store = SailRepository(MemoryStore()).also { it.init() }
            val vf = store.valueFactory
            store.connection.use { conn ->
                conn.add(vf.createIRI(ex + "s"), vf.createIRI(ex + "p"), vf.createLiteral("ok"))
                conn.add(vf.createIRI(ex + "s"), vf.createIRI(ex + "p"), vf.createLiteral("bad", "en_US"))
            }
            return Rdf4jRepository(store, false, lenient)
        }
        wrapped(false).use { strict ->
            assertFailsWith<IllegalArgumentException> { strict.defaultGraph.getTriples() }
        }
        wrapped(true).use { lenient ->
            val triples = lenient.defaultGraph.getTriples()
            assertEquals(listOf(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("ok"))), triples)
            assertEquals(triples.size, lenient.defaultGraph.size())
            assertFalse(lenient.defaultGraph.find(null, null, null).isEmpty())
        }
        Rdf4jProvider().createRepository("memory", RdfConfig(providerId = "rdf4j", variantId = "memory", options = mapOf("lenientRead" to "true"))).use { repo ->
            assertTrue((repo as Rdf4jRepository).lenientRead)
            repo.update(UpdateQuery("INSERT DATA { <${ex}s> <${ex}p> \"x\" }"))
            assertEquals(1, repo.defaultGraph.size())
        }
    }
}
