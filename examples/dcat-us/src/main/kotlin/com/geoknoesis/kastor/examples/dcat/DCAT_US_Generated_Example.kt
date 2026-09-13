/**
 * Kastor Gen code generation from the DCAT-US 3.0 SHACL shapes.
 *
 * The `@file:Rdf` annotation makes the Kastor KSP processor (added to this project by the root build) read
 * `src/main/resources/dcat-us_3.0_shacl_shapes.ttl` and `dcat-us_3.0_context.jsonld` and generate one
 * interface + RDF-backed wrapper per node shape into `com.geoknoesis.kastor.examples.dcat.generated`.
 * The JSON-LD context supplies distinct type names where two vocabularies use the same local name
 * (`vcard:Address` → `VcardAddress`, `locn:Address` → `LocnAddress`).
 *
 * Run: ./gradlew :examples:dcat-us:runGeneratedExample
 */
@file:Rdf(
    shacl = "dcat-us_3.0_shacl_shapes.ttl",
    context = "dcat-us_3.0_context.jsonld",
    packageName = "com.geoknoesis.kastor.examples.dcat.generated",
    validationAnnotations = ValidationAnnotations.NONE,
)

package com.geoknoesis.kastor.examples.dcat

import com.geoknoesis.kastor.examples.dcat.generated.Catalog
import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.runtime.asRdf
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF

fun main() {
    val graph = MemoryGraph()
    val catalog = Iri("https://data.example.gov/catalog")
    graph.addTriple(RdfTriple(catalog, RDF.type, Iri("http://www.w3.org/ns/dcat#Catalog")))
    graph.addTriple(RdfTriple(catalog, Iri("http://purl.org/dc/terms/title"), Literal("Example Government Data Catalog")))

    // `Catalog` is generated at build time from the DCAT-US shapes.
    val typed: Catalog = graph.materialize(catalog)
    println("Materialized ${typed::class.simpleName} for ${typed.asRdf().node}")
}
