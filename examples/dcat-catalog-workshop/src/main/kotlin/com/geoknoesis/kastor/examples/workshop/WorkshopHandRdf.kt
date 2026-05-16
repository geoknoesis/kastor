package com.geoknoesis.kastor.examples.workshop

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.dsl.dcat
import com.geoknoesis.kastor.rdf.dsl.dcterms
import com.geoknoesis.kastor.rdf.dsl.voidMeta
import com.geoknoesis.kastor.rdf.sparql.asFlow
import com.geoknoesis.kastor.rdf.sparql.getAs
import com.geoknoesis.kastor.rdf.sparql.select
import com.geoknoesis.kastor.rdf.var_
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Part A — Kastor RDF: in-memory repo, DCAT / DCTerms / VoID DSL,
 * **SPARQL query DSL** (`select` / `prefix` / `where` / `triple` / `values`), typed bindings, Flow.
 *
 * The object returned from `select { … }` exposes `sparql` with the serialized query string.
 */
fun workshopHandRdf() {
    val catalog = Iri("http://example.org/workshop/catalog")
    val dataset = Iri("http://example.org/workshop/dataset")
    val distribution = Iri("http://example.org/workshop/distribution")
    val publisher = Iri("http://example.org/workshop/publisher")

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
            dataset.description("Built incrementally in the workshop.")
            distribution.title("Tabular download")
            catalog.publisher(publisher)
            publisher.title("GeoKnoesis Workshop")
        }
        dcat {
            distribution.accessURL("https://example.org/workshop/data.csv")
        }
        voidMeta {
            val voidDesc = bnode("voidDatasetDesc")
            voidDesc voidRoot dataset
            voidDesc.voidTriples(42L)
        }
    }

    val titleQuery = select("title") {
        prefix("dct", DCTERMS.namespace)
        where {
            triple(dataset, DCTERMS.title, var_("title"))
        }
    }
    val title = repo.select(titleQuery).first()?.getAs<String>("title")
    println("SPARQL dataset title: $title")

    runBlocking {
        val distributionQuery = select("dist", "t", "url") {
            prefix("dcat", DCAT.namespace)
            prefix("dct", DCTERMS.namespace)
            where {
                values(var_("ds"), dataset)
                triple(var_("ds"), DCAT.distributionProp, var_("dist"))
                triple(var_("dist"), DCTERMS.title, var_("t"))
                triple(var_("dist"), DCAT.accessURL, var_("url"))
            }
        }
        val rows = repo.select(distributionQuery).asFlow("dist", "t", "url").toList()
        println("Distributions (via Flow + typed columns): ${rows.size}")
        rows.forEach { r ->
            println("  - ${r.getAs<Iri>("dist")} title=${r.getAs<String>("t")} url=${r.getAs<String>("url")}")
        }
    }

    println("Triple count: ${repo.defaultGraph.size()}")
    repo.close()
}
