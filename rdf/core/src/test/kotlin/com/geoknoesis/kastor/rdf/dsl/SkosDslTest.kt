package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkosDslTest {

    @Test
    fun `skos DSL emits broader narrower prefLabel and scheme triples`() {
        val scheme = Iri("http://example.org/scheme")
        val country = Iri("http://example.org/country")
        val city = Iri("http://example.org/city")

        val repo = Rdf.memory()
        repo.add {
            skos {
                scheme hasTopConcept country
                country topConceptOf scheme
                country.prefLabel("Country", "en")
                city.prefLabel("City")
                city broader country
                country narrower city
                city inScheme scheme
            }
            country `is` SKOS.Concept
            scheme `is` SKOS.ConceptScheme
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == scheme && it.predicate == SKOS.hasTopConcept && it.obj == country })
        assertTrue(triples.any { it.subject == country && it.predicate == SKOS.topConceptOf && it.obj == scheme })
        assertTrue(triples.any { it.subject == country && it.predicate == SKOS.prefLabel && it.obj == lang("Country", "en") })
        assertTrue(triples.any { it.subject == city && it.predicate == SKOS.prefLabel && it.obj == string("City") })
        assertTrue(triples.any { it.subject == city && it.predicate == SKOS.broader && it.obj == country })
        assertTrue(triples.any { it.subject == country && it.predicate == SKOS.narrower && it.obj == city })
        assertTrue(triples.any { it.subject == city && it.predicate == SKOS.inScheme && it.obj == scheme })
        assertTrue(triples.any { it.subject == country && it.predicate == RDF.type && it.obj == SKOS.Concept })
        assertTrue(triples.any { it.subject == scheme && it.predicate == RDF.type && it.obj == SKOS.ConceptScheme })
    }

    @Test
    fun `skos QName resolves in graph DSL`() {
        val g = Rdf.graph {
            val x = Iri("http://example.org/x")
            x - RDF.type - qname("skos:Concept")
        }
        assertEquals(SKOS.Concept, g.getTriples().single().obj)
    }
}
