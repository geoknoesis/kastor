package com.geoknoesis.kastor.gen.processor.internal.utils

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
     */
    fun toKotlinType(property: ShaclProperty, context: JsonLdContext): TypeName {
        return when {
            property.targetClass != null -> mapObjectProperty(property)
            else -> mapLiteralProperty(property)
        }
    }
    
    private fun mapObjectProperty(property: ShaclProperty): TypeName {
        val targetInterfaceName = NamingUtils.extractInterfaceName(property.targetClass!!)
        val targetType = ClassName("", targetInterfaceName)
        
        return when {
            property.maxCount == null || property.maxCount > 1 -> 
                KotlinPoetUtils.listOf(targetType)
            property.minCount == null || property.minCount == 0 -> 
                targetType.copy(nullable = true)
            else -> 
                targetType
        }
    }
    
    private fun mapLiteralProperty(property: ShaclProperty): TypeName {
        val baseType = mapDatatype(property.datatype)
        
        return when {
            property.maxCount == null || property.maxCount > 1 -> 
                KotlinPoetUtils.listOf(baseType)
            property.minCount == null || property.minCount == 0 -> 
                baseType.copy(nullable = true)
            else -> 
                baseType
        }
    }
    
    private fun mapDatatype(datatype: String?): TypeName {
        return when (datatype) {
            "http://www.w3.org/2001/XMLSchema#string" -> String::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#int",
            "http://www.w3.org/2001/XMLSchema#integer" -> Int::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#double",
            "http://www.w3.org/2001/XMLSchema#float" -> Double::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#boolean" -> Boolean::class.asTypeName()
            "http://www.w3.org/2001/XMLSchema#anyURI" -> String::class.asTypeName()
            else -> String::class.asTypeName() // Default to String for unknown types
        }
    }
}


