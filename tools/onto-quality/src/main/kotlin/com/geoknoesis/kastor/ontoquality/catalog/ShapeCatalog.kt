package com.geoknoesis.kastor.ontoquality.catalog

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.RdfGraph

interface ShapeCatalog {
    val id: String
    val name: String
    val version: String
    val shapeMetadata: Map<String, ShapeMetadata>
    fun loadShapesGraph(): RdfGraph
}

data class ShapeMetadata(
    val category: QualityCategory,
    val pitfall: PitfallReference?,
    val tier: QualityTier,
)
