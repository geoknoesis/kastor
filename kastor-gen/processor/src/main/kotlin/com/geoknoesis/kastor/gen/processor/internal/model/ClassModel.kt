package com.geoknoesis.kastor.gen.processor.internal.model

import com.geoknoesis.kastor.rdf.MutableRdfGraph

public data class ClassModel(
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val classIri: String,
    val properties: List<PropertyModel>
)

public data class PropertyModel(
    val name: String,
    val kotlinType: String,
    val predicateIri: String,
    val type: PropertyType,
    /** When true, generated wrapper uses `override var` and writes through a [MutableRdfGraph]. */
    val mutable: Boolean = false,
)

public enum class PropertyType {
    LITERAL,
    OBJECT,
    OBJECT_LIST
}













