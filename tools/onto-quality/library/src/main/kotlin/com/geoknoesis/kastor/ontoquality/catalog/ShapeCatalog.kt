package com.geoknoesis.kastor.ontoquality.catalog

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.RdfGraph

interface ShapeCatalog {
    val id: String
    val name: String
    val version: String

    /**
     * Maps shape resource IRIs (and optionally extension-specific keys such as engine **violationCode** strings)
     * to reporting metadata. Lookup tries the violation's shape IRI first, then **violationCode** when the URI is absent.
     */
    val shapeMetadata: Map<String, ShapeMetadata>
    fun loadShapesGraph(): RdfGraph
}

data class ShapeMetadata(
    val category: QualityCategory,
    val pitfall: PitfallReference?,
    val tier: QualityTier,
)
