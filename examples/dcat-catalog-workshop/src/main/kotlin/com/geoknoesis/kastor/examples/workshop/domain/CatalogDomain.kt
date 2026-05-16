@file:Rdf(
  prefixes = [
    com.geoknoesis.kastor.gen.annotations.Prefix("dcat", "http://www.w3.org/ns/dcat#"),
    com.geoknoesis.kastor.gen.annotations.Prefix("dct", "http://purl.org/dc/terms/"),
  ],
)

package com.geoknoesis.kastor.examples.workshop.domain

import com.geoknoesis.kastor.gen.annotations.Rdf

/**
 * Minimal DCAT-shaped domain model for the workshop. KSP generates `*Wrapper` classes
 * and registers them with [com.geoknoesis.kastor.gen.runtime.OntoMapper].
 */
@Rdf(iri = "dcat:Catalog")
interface WorkshopCatalog {
  @Rdf(iri = "dct:title")
  val title: List<String>

  @Rdf(iri = "dcat:dataset")
  val dataset: List<WorkshopDataset>
}

@Rdf(iri = "dcat:Dataset")
interface WorkshopDataset {
  @Rdf(iri = "dct:title")
  val title: List<String>

  @Rdf(iri = "dcat:distribution")
  val distribution: List<WorkshopDistribution>
}

@Rdf(iri = "dcat:Distribution")
interface WorkshopDistribution {
  @Rdf(iri = "dct:title")
  val title: List<String>

  @Rdf(iri = "dcat:accessURL")
  val accessURL: List<String>
}
