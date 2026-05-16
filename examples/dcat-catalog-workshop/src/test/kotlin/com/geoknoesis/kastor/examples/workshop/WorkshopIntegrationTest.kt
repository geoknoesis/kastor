package com.geoknoesis.kastor.examples.workshop

import com.geoknoesis.kastor.examples.workshop.domain.WorkshopCatalog
import com.geoknoesis.kastor.gen.runtime.OntoMapper
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.dsl.dcat
import com.geoknoesis.kastor.rdf.dsl.dcterms
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.testing.assertDefaultGraphIsomorphicTurtle
import org.junit.jupiter.api.Test

class WorkshopIntegrationTest {

    @Test
    fun `hand-built graph matches golden turtle shape`() {
        val catalog = Iri("http://example.org/wt/catalog")
        val dataset = Iri("http://example.org/wt/dataset")
        val dist = Iri("http://example.org/wt/dist")

        val repo = Rdf.memory()
        repo.add {
            catalog - RDF.type - DCAT.Catalog
            dataset - RDF.type - DCAT.Dataset
            dist - RDF.type - DCAT.Distribution
            dcat {
                catalog.dataset(dataset)
                dataset.distribution(dist)
            }
            dcterms {
                catalog.title("T")
                dataset.title("D")
                dist.title("X")
            }
            dcat { dist.accessURL("https://example.org/x") }
        }

        val expected = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dct: <http://purl.org/dc/terms/> .
            <http://example.org/wt/catalog> a dcat:Catalog ;
                dct:title "T" ;
                dcat:dataset <http://example.org/wt/dataset> .
            <http://example.org/wt/dataset> a dcat:Dataset ;
                dct:title "D" ;
                dcat:distribution <http://example.org/wt/dist> .
            <http://example.org/wt/dist> a dcat:Distribution ;
                dct:title "X" ;
                dcat:accessURL "https://example.org/x" .
        """.trimIndent()

        assertDefaultGraphIsomorphicTurtle(expected, repo)
        repo.close()
    }

    @Test
    fun `materialize workshop catalog from graph`() {
        OntoMapper.initialize(WorkshopCatalog::class.java)

        val catalog = Iri("http://example.org/wt/catalog")
        val dataset = Iri("http://example.org/wt/dataset")
        val dist = Iri("http://example.org/wt/dist")

        val repo = Rdf.memory()
        repo.add {
            catalog - RDF.type - DCAT.Catalog
            dataset - RDF.type - DCAT.Dataset
            dist - RDF.type - DCAT.Distribution
            dcat {
                catalog.dataset(dataset)
                dataset.distribution(dist)
            }
            dcterms {
                catalog.title("T")
                dataset.title("D")
                dist.title("X")
            }
            dcat { dist.accessURL("https://example.org/x") }
        }

        val view = repo.defaultGraph.materialize<WorkshopCatalog>(catalog)
        org.junit.jupiter.api.Assertions.assertEquals("T", view.title.first())
        org.junit.jupiter.api.Assertions.assertEquals("D", view.dataset.first().title.first())
        val d0 = view.dataset.first().distribution.first()
        org.junit.jupiter.api.Assertions.assertEquals("X", d0.title.first())
        org.junit.jupiter.api.Assertions.assertEquals("https://example.org/x", d0.accessURL.first())

        repo.close()
    }
}
