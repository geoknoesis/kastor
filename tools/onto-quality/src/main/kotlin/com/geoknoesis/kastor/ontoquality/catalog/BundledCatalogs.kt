package com.geoknoesis.kastor.ontoquality.catalog

object BundledCatalogs {
    val OWL_QUALITY: ShapeCatalog =
        ResourceCatalog(
            id = "owl-quality",
            name = "OWL Ontology Quality",
            version = "0.1.0",
            resourcePath = "/shapes/owl-quality-shacl.ttl",
        )
    val SKOS_VALIDATION: ShapeCatalog =
        ResourceCatalog(
            id = "skos-validation",
            name = "SKOS Taxonomy Validation",
            version = "0.1.0",
            resourcePath = "/shapes/skos-validation-shacl.ttl",
        )
    val DATA_QUALITY: ShapeCatalog =
        ResourceCatalog(
            id = "data-quality",
            name = "Data Quality Constraints",
            version = "0.1.0",
            resourcePath = "/shapes/dq-constraints-shacl.ttl",
        )

    val EMBEDDING_QUALITY: ShapeCatalog =
        ResourceCatalog(
            id = "embedding-quality",
            name = "Embedding-based Ontology Quality",
            version = "0.2.0",
            resourcePath = "/shapes/embedding-quality-shacl.ttl",
        )

    val MODERN_ENGINEERING: ShapeCatalog =
        ResourceCatalog(
            id = "modern-engineering",
            name = "Modern Ontology Engineering",
            version = "0.1.0",
            resourcePath = "/shapes/modern-engineering-shacl.ttl",
        )

    val RDF12_QUALITY: ShapeCatalog =
        ResourceCatalog(
            id = "rdf12-quality",
            name = "RDF 1.2 Conformance",
            version = "0.1.0",
            resourcePath = "/shapes/rdf12-quality-shacl.ttl",
        )

    val all: List<ShapeCatalog> =
        listOf(
            OWL_QUALITY,
            SKOS_VALIDATION,
            DATA_QUALITY,
            EMBEDDING_QUALITY,
            MODERN_ENGINEERING,
            RDF12_QUALITY,
        )
}
