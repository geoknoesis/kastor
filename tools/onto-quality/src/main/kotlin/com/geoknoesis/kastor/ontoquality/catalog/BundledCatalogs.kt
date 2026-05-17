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

    /**
     * **Documentation only:** methodological pitfalls when using SHACL for QC,
     * expressed as **`sh:NodeShape`** resources in the same tagging style as [OWL_QUALITY]
     * (`oqsh:pitfall` / `oqsh:category`) and [SKOS_VALIDATION] (`skvsh:rule` / `skvsh:category`).
     *
     * Every shape has **`sh:deactivated true`** so it never validates domain data.
     * Not included in [all] or [SKOS_VOCABULARY_QC]; load explicitly for registries or tooling.
     */
    val SHACL_QC_DESIGN: ShapeCatalog =
        ResourceCatalog(
            id = "shacl-qc-design",
            name = "SHACL QC methodology (documentation shapes)",
            version = "0.1.0",
            resourcePath = "/shapes/shacl-qc-design-shacl.ttl",
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

    /**
     * Recommended stack for **published SKOS vocabularies** and lightweight RDF datasets:
     * SKOS integrity and conventions, data-quality shapes, modern engineering / FAIR-style rules,
     * and RDF 1.2 hygiene — **without** [OWL_QUALITY] (often noisy for pure SKOS).
     *
     * For semantic-similarity shapes, use [SKOS_VOCABULARY_QC_WITH_EMBEDDING] after
     * [com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher].
     */
    val SKOS_VOCABULARY_QC: List<ShapeCatalog> =
        listOf(
            SKOS_VALIDATION,
            DATA_QUALITY,
            MODERN_ENGINEERING,
            RDF12_QUALITY,
        )

    val SKOS_VOCABULARY_QC_WITH_EMBEDDING: List<ShapeCatalog> =
        SKOS_VOCABULARY_QC + EMBEDDING_QUALITY
}
