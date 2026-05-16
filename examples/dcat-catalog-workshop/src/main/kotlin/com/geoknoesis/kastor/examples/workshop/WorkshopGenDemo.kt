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

/**
 * Part B — Kastor Gen: the same graph shape as Part A, read through `@Rdf` domain interfaces
 * (KSP generates wrappers on `./gradlew :examples:dcat-catalog-workshop:compileKotlin`).
 */
fun workshopGenMaterialize() {
    OntoMapper.initialize(WorkshopCatalog::class.java)

    val catalog = Iri("http://example.org/workshop/catalog")
    val dataset = Iri("http://example.org/workshop/dataset")
    val distribution = Iri("http://example.org/workshop/distribution")

    val repo = Rdf.memory()
    repo.add {
        catalog - RDF.type - DCAT.Catalog
        dataset - RDF.type - DCAT.Dataset
        distribution - RDF.type - DCAT.Distribution
        dcat {
            catalog.dataset(dataset)
            dataset.distribution(distribution)
        }
        dcterms {
            catalog.title("Open data class catalog")
            dataset.title("Lesson dataset")
            distribution.title("Tabular download")
        }
        dcat {
            distribution.accessURL("https://example.org/workshop/data.csv")
        }
    }

    val view: WorkshopCatalog = repo.defaultGraph.materialize(catalog)
    println("Materialized catalog title: ${view.title.firstOrNull()}")
    val ds = view.dataset.firstOrNull()
    println("  dataset: ${ds?.title?.firstOrNull()}")
    val dist = ds?.distribution?.firstOrNull()
    println("  distribution: ${dist?.title?.firstOrNull()} @ ${dist?.accessURL?.firstOrNull()}")

    repo.close()
}
