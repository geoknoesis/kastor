package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.vocab.PROV
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.Vocabularies
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProvDslTest {

    @Test
    fun `prov DSL emits wasGeneratedBy used wasAttributedTo and time`() {
        val dataset = Iri("http://example.org/dataset")
        val extraction = Iri("http://example.org/extraction")
        val rawFile = Iri("http://example.org/raw.ttl")
        val agent = Iri("http://example.org/agent")

        val repo = Rdf.memory()
        repo.add {
            prov {
                dataset wasGeneratedBy extraction
                extraction used rawFile
                dataset wasAttributedTo agent
                extraction.startedAtTime("2024-01-15T10:00:00")
                extraction.provLabel("Extract raw data", "en")
            }
            dataset `is` PROV.Entity
            extraction `is` PROV.Activity
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == dataset && it.predicate == PROV.wasGeneratedBy && it.obj == extraction })
        assertTrue(triples.any { it.subject == extraction && it.predicate == PROV.used && it.obj == rawFile })
        assertTrue(triples.any { it.subject == dataset && it.predicate == PROV.wasAttributedTo && it.obj == agent })
        assertTrue(
            triples.any {
                it.subject == extraction &&
                    it.predicate == PROV.startedAtTime &&
                    (it.obj as? Literal)?.datatype == XSD.dateTime &&
                    (it.obj as? Literal)?.lexical?.startsWith("2024-01-15T10") == true
            },
        )
        assertTrue(triples.any { it.subject == dataset && it.predicate == RDF.type && it.obj == PROV.Entity })
        assertTrue(triples.any { it.subject == extraction && it.predicate == RDF.type && it.obj == PROV.Activity })
        assertTrue(
            triples.any {
                it.subject == extraction &&
                    it.predicate == PROV.label &&
                    (it.obj as? Literal)?.lexical == "Extract raw data"
            },
        )
    }

    @Test
    fun `prov QName resolves in graph DSL`() {
        val g = Rdf.graph {
            val x = Iri("http://example.org/x")
            x - RDF.type - qname("prov:Activity")
        }
        assertEquals(PROV.Activity, g.getTriples().single().obj)
    }

    @Test
    fun `PROV vocabulary is registered`() {
        assertEquals(PROV, Vocabularies.findByPrefix("prov"))
        assertEquals(PROV, Vocabularies.findByNamespace("http://www.w3.org/ns/prov#"))
    }
}
