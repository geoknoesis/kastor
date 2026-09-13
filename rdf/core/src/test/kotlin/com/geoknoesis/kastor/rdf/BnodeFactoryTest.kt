package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.alt
import com.geoknoesis.kastor.rdf.dsl.bag
import com.geoknoesis.kastor.rdf.dsl.list
import com.geoknoesis.kastor.rdf.dsl.owl
import com.geoknoesis.kastor.rdf.dsl.seq
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Blank nodes minted by the DSLs must never collide, because providers keep the label as identity
 * and every `repo.add { }` call builds a fresh DSL instance.
 */
class BnodeFactoryTest {

    private fun blankNodes(triples: Collection<RdfTriple>): Set<BlankNode> =
        triples.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().toSet()

    @Test
    fun `lists added in separate repository add calls stay separate`() {
        val repo = Rdf.memory()
        val a = Iri("http://example.org/a")
        val b = Iri("http://example.org/b")
        val p = Iri("http://example.org/p")

        repo.add { a - p - list("x") }
        repo.add { b - p - list("y") }

        val graph = repo.defaultGraph
        val headA = graph.find(a, p).single().obj as RdfResource
        val headB = graph.find(b, p).single().obj as RdfResource
        assertTrue(headA != headB, "Each add call must mint its own list head")
        assertEquals(listOf(string("x")), graph.find(headA, RDF.first).map { it.obj })
        assertEquals(listOf(string("y")), graph.find(headB, RDF.first).map { it.obj })
        repo.close()
    }

    @Test
    fun `labels are unique across DSL instances and DSL kinds`() {
        val person = Iri("http://example.org/person")
        val graphs = List(3) {
            Rdf.graph {
                person - FOAF.mbox - list(string("email1"), string("email2"))
                person - DCTERMS.subject - bag(string("Tech"), string("AI"))
                person - FOAF.knows - seq(person, person)
                person - FOAF.mbox - alt(string("email1"), string("email2"))
            }
        }
        val ontology = owl {
            prefix("ex", "http://example.org/")
            `class`("ex:A") { equivalentClass { unionOf("ex:B", "ex:C") } }
        }

        val perGraph = graphs.map { blankNodes(it.getTriples()) }
        perGraph.forEach { assertEquals(5, it.size, "list(2) + bag + seq + alt") }
        val all = perGraph.flatten() + blankNodes(ontology.getTriples())
        assertEquals(all.size, all.toSet().size, "No blank node may be shared between DSL instances")
        all.forEach { assertTrue(it.id.matches(Regex("[A-Za-z][A-Za-z0-9_]*")), "Label '${it.id}' must be a valid Turtle label") }
    }

    @Test
    fun `labels are unique within one DSL instance`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        repo.add {
            person - FOAF.mbox - list(string("email1"), string("email2"))
            person - DCTERMS.subject - bag(string("Tech"), string("AI"))
            person - FOAF.knows - seq(person, person)
            person - FOAF.mbox - alt(string("email1"), string("email2"))
            person - DCTERMS.subject - bag(string("More"), string("Tech"))
        }
        assertEquals(6, blankNodes(repo.defaultGraph.getTriples()).size)
        repo.close()
    }
}
