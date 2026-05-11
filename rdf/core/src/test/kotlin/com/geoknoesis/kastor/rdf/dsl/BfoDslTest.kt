package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.vocab.BFO
import com.geoknoesis.kastor.rdf.vocab.OBO
import com.geoknoesis.kastor.rdf.vocab.RO
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.Vocabularies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BfoDslTest {

    @Test
    fun `bfo DSL emits expected partOf and type triples`() {
        val cell = Iri("http://example.org/cell")
        val tissue = Iri("http://example.org/tissue")
        val process = Iri("http://example.org/process")

        val repo = Rdf.memory()
        repo.add {
            bfo {
                cell partOf tissue
                tissue participatesIn process
                process hasParticipant cell
            }
            cell `is` BFO.materialEntity
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(
            triples.any { it.subject == cell && it.predicate == BFO.partOf && it.obj == tissue },
        )
        assertTrue(
            triples.any { it.subject == tissue && it.predicate == RO.participatesIn && it.obj == process },
        )
        assertTrue(
            triples.any { it.subject == process && it.predicate == RO.hasParticipant && it.obj == cell },
        )
        assertTrue(
            triples.any { it.subject == cell && it.predicate == RDF.type && it.obj == BFO.materialEntity },
        )
    }

    @Test
    fun `obo QName resolves in graph DSL`() {
        val g = Rdf.graph {
            val x = Iri("http://example.org/x")
            x - RDF.type - qname("obo:BFO_0000040")
        }
        assertEquals(BFO.materialEntity, g.getTriples().single().obj)
    }

    @Test
    fun `OBO vocabulary is registered`() {
        assertEquals(OBO, Vocabularies.findByPrefix("obo"))
        assertEquals(OBO, Vocabularies.findByNamespace("http://purl.obolibrary.org/obo/"))
    }
}
