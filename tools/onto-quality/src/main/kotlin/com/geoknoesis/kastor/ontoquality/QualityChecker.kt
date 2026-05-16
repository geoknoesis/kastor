package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class QualityChecker private constructor(
    private val validator: ShaclValidator,
    private val catalogs: List<ShapeCatalog>,
) {
    private val mergedShapes: RdfGraph by lazy { Companion.mergeCatalogShapes(catalogs) }

    private val metadataByShape: Map<String, ShapeMetadata> by lazy {
        catalogs.fold(emptyMap()) { acc, c -> acc + c.shapeMetadata }
    }

    fun check(ontology: RdfGraph): QualityReport {
        val raw = validator.validate(ontology, mergedShapes)
        return QualityReport.from(raw, catalogs)
    }

    fun checkResource(ontology: RdfGraph, resource: RdfResource): QualityReport {
        val raw = validator.validateResource(ontology, mergedShapes, resource)
        return QualityReport.from(raw, catalogs)
    }

    fun checkAsync(ontology: RdfGraph): Flow<QualityFinding> =
        validator.validateViolationsFlow(ontology, mergedShapes).map { v ->
            QualityFinding.from(v, metadataByShape)
        }

    class Builder(private val validator: ShaclValidator) {
        private val catalogs = mutableListOf<ShapeCatalog>()

        fun addCatalog(catalog: ShapeCatalog): Builder {
            catalogs.add(catalog)
            return this
        }

        fun withAllBundledCatalogs(): Builder {
            catalogs.clear()
            catalogs.addAll(BundledCatalogs.all)
            return this
        }

        fun build(): QualityChecker {
            require(catalogs.isNotEmpty()) { "At least one ShapeCatalog is required" }
            return QualityChecker(validator, catalogs.toList())
        }
    }

    companion object {
        fun default(validator: ShaclValidator): QualityChecker =
            builder(validator).withAllBundledCatalogs().build()

        fun builder(validator: ShaclValidator): Builder = Builder(validator)

        private fun mergeCatalogShapes(catalogs: List<ShapeCatalog>): RdfGraph {
            val merged = JenaBridge.createEmptyModel()
            for (c in catalogs) {
                merged.addTriples(c.loadShapesGraph().getTriples())
            }
            return merged
        }
    }
}
