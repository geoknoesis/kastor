package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName

/**
 * Unified type mapper for converting SHACL properties to Kotlin types.
 * Single source of truth for type mapping logic.
 */
internal object TypeMapper {

    /**
     * Maps a SHACL property to a Kotlin TypeName.
     *
     * [nestedMode] and [dataClassSuffix] only affect object properties and are ignored for literals.
     *   - [NestedMode.INTERFACE]  → property type is the generated interface (default, no suffix applied)
     *   - [NestedMode.DATA_CLASS] → property type is the generated data class (interface name + [dataClassSuffix])
     *   - [NestedMode.IRI_ONLY]   → property type is String (IRI value, no sub-object materialisation)
     */
    fun toKotlinType(
        property: ShaclProperty,
        context: JsonLdContext,
        nestedMode: NestedMode = NestedMode.INTERFACE,
        dataClassSuffix: String = "",
    ): TypeName {
        return when {
            property.targetClass != null -> mapObjectProperty(property, nestedMode, dataClassSuffix)
            else -> mapLiteralProperty(property)
        }
    }

    private fun mapObjectProperty(
        property: ShaclProperty,
        nestedMode: NestedMode,
        dataClassSuffix: String,
    ): TypeName {
        val baseName = NamingUtils.extractInterfaceName(property.targetClass!!)
        val targetType: TypeName = when (nestedMode) {
            NestedMode.INTERFACE  -> ClassName("", baseName)
            NestedMode.DATA_CLASS -> ClassName("", "$baseName$dataClassSuffix")
            NestedMode.IRI_ONLY   -> String::class.asTypeName()
        }

        return applyCardinality(targetType, property)
    }

    private fun mapLiteralProperty(property: ShaclProperty): TypeName {
        val baseType = mapDatatype(property.datatype)
        return applyCardinality(baseType, property)
    }

    private fun applyCardinality(baseType: TypeName, property: ShaclProperty): TypeName =
        when {
            property.maxCount == null || property.maxCount > 1 ->
                KotlinPoetUtils.listOf(baseType.copy(nullable = false))
            property.minCount == null || property.minCount == 0 ->
                baseType.copy(nullable = true)
            else ->
                baseType.copy(nullable = false)
        }

    internal fun mapDatatype(datatype: String?): TypeName {
        return when (datatype) {
            "http://www.w3.org/2001/XMLSchema#string"  -> String::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#int",
            "http://www.w3.org/2001/XMLSchema#integer" -> Int::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#double",
            "http://www.w3.org/2001/XMLSchema#float"   -> Double::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#boolean" -> Boolean::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#anyURI"  -> String::class.asTypeName()
            else -> String::class.asTypeName()
        }
    }
}


