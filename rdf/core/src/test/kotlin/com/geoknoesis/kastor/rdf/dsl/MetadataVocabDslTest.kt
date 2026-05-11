package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.toLiteral
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.GEO
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.TIME
import com.geoknoesis.kastor.rdf.vocab.VOID
import com.geoknoesis.kastor.rdf.vocab.Vocabularies
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataVocabDslTest {

    @Test
    fun `dcat DSL links catalog dataset and distribution`() {
        val cat = Iri("http://example.org/catalog")
        val ds = Iri("http://example.org/dataset")
        val dist = Iri("http://example.org/dist")

        val repo = Rdf.memory()
        repo.add {
            dcat {
                cat dataset ds
                ds distribution dist
                dist.downloadURL("http://example.org/file.csv")
                dist.mediaType("text/csv")
            }
            cat `is` DCAT.Catalog
            ds `is` DCAT.Dataset
            dist `is` DCAT.Distribution
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == cat && it.predicate == DCAT.datasetProp && it.obj == ds })
        assertTrue(triples.any { it.subject == ds && it.predicate == DCAT.distributionProp && it.obj == dist })
        assertTrue(triples.any { it.subject == dist && it.predicate == DCAT.downloadURL && it.obj == string("http://example.org/file.csv") })
    }

    @Test
    fun `dcterms DSL adds title and creator`() {
        val doc = Iri("http://example.org/doc")
        val agent = Iri("http://example.org/agent")

        val repo = Rdf.memory()
        repo.add {
            dcterms {
                doc.title("Hello", "en")
                doc creator agent
            }
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == doc && it.predicate == DCTERMS.title && it.obj == lang("Hello", "en") })
        assertTrue(triples.any { it.subject == doc && it.predicate == DCTERMS.creator && it.obj == agent })
    }

    @Test
    fun `voidMeta DSL adds triple count and endpoint`() {
        val vd = Iri("http://example.org/void")

        val repo = Rdf.memory()
        repo.add {
            voidMeta {
                vd.voidTriples(42L)
                vd.sparqlEndpoint("http://example.org/sparql")
            }
            vd `is` VOID.DatasetDescription
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == vd && it.predicate == VOID.triples && it.obj == 42L.toLiteral() })
        assertTrue(triples.any { it.subject == vd && it.predicate == VOID.sparqlEndpoint && it.obj == string("http://example.org/sparql") })
    }

    @Test
    fun `geo DSL links feature to WKT geometry`() {
        val feature = Iri("http://example.org/feature")
        val geom = Iri("http://example.org/geom")

        val repo = Rdf.memory()
        repo.add {
            geo {
                feature hasGeometry geom
                geom.asWkt("POINT(-71 42)")
            }
            feature `is` GEO.Feature
            geom `is` GEO.Geometry
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == feature && it.predicate == GEO.hasGeometry && it.obj == geom })
        assertTrue(
            triples.any {
                it.subject == geom &&
                    it.predicate == GEO.asWkt &&
                    (it.obj as? Literal)?.datatype == GEO.wktLiteral &&
                    (it.obj as? Literal)?.lexical?.contains("POINT") == true
            },
        )
    }

    @Test
    fun `time DSL orders intervals`() {
        val a = Iri("http://example.org/a")
        val b = Iri("http://example.org/b")

        val repo = Rdf.memory()
        repo.add {
            time {
                a before b
            }
        }

        val triples = repo.defaultGraph.getTriples().toSet()
        assertTrue(triples.any { it.subject == a && it.predicate == TIME.before && it.obj == b })
    }

    @Test
    fun `QName resolution for new prefixes`() {
        val g = Rdf.graph {
            val x = Iri("http://example.org/x")
            x - RDF.type - qname("dcat:Dataset")
        }
        assertEquals(DCAT.Dataset, g.getTriples().single().obj)
    }

    @Test
    fun `VOID GEO TIME registered in Vocabularies`() {
        assertEquals(VOID, Vocabularies.findByPrefix("void"))
        assertEquals(GEO, Vocabularies.findByPrefix("geo"))
        assertEquals(TIME, Vocabularies.findByPrefix("time"))
    }
}
