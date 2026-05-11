package com.geoknoesis.kastor.gen.processor.internal.model

data class ClassModel(
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val classIri: String,
    val properties: List<PropertyModel>
)

data class PropertyModel(
    val name: String,
    val kotlinType: String,
    val predicateIri: String,
    val type: PropertyType,
    /** When true, generated wrapper uses `override var` and writes through a [MutableRdfGraph]. */
    val mutable: Boolean = false,
)

enum class PropertyType {
    LITERAL,
    OBJECT,
    OBJECT_LIST
}













