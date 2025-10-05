package com.example.ontomapper.processor.model

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
    val type: PropertyType
)

enum class PropertyType {
    LITERAL,
    OBJECT,
    OBJECT_LIST
}
