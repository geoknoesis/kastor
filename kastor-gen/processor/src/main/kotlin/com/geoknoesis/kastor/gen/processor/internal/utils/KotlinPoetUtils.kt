package com.geoknoesis.kastor.gen.processor.internal.utils

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import java.lang.reflect.Constructor

/**
 * Utility functions for working with KotlinPoet.
 * 
 * These functions provide a clean API for creating parameterized types
 * while encapsulating access to KotlinPoet's internal APIs.
 * 
 * All @Suppress annotations are centralized here to keep the rest of the codebase clean.
 */
internal object KotlinPoetUtils {
    
    // Cache the ParameterizedTypeName constructor via reflection
    private val parameterizedTypeNameConstructor: Constructor<ParameterizedTypeName> by lazy {
        @Suppress("UNCHECKED_CAST")
        ParameterizedTypeName::class.java.getDeclaredConstructor(
            TypeName::class.java,  // enclosingType
            ClassName::class.java,  // rawType
            List::class.java,       // typeArguments
            Boolean::class.java,    // nullable
            java.util.List::class.java,  // annotations
            java.util.Map::class.java     // tags
        ).apply {
            isAccessible = true
        } as Constructor<ParameterizedTypeName>
    }
    
    /**
     * Creates a parameterized type (e.g., List<String>, MutableList<RdfResource>).
     * 
     * This function encapsulates access to KotlinPoet's internal ParameterizedTypeName constructor,
     * keeping the rest of the codebase clean and readable.
     * 
     * @param rawType The base type (e.g., List, MutableList, Set, Map)
     * @param typeArguments The type arguments (e.g., String, RdfResource)
     * @return A ParameterizedTypeName representing the parameterized type
     */
    fun parameterizedType(rawType: ClassName, vararg typeArguments: TypeName): ParameterizedTypeName {
        @Suppress("UNCHECKED_CAST")
        return parameterizedTypeNameConstructor.newInstance(
            null,  // enclosingType
            rawType,
            typeArguments.toList(),
            false,  // nullable
            emptyList<Any>(),  // annotations
            emptyMap<Any, Any>()  // tags
        )
    }
    
    /**
     * Convenience function to create List<T> type.
     */
    fun listOf(elementType: TypeName): ParameterizedTypeName {
        return parameterizedType(ClassName("kotlin.collections", "List"), elementType)
    }
    
    /**
     * Convenience function to create MutableList<T> type.
     */
    fun mutableListOf(elementType: TypeName): ParameterizedTypeName {
        return parameterizedType(ClassName("kotlin.collections", "MutableList"), elementType)
    }
    
    /**
     * Convenience function to create Set<T> type.
     */
    fun setOf(elementType: TypeName): ParameterizedTypeName {
        return parameterizedType(ClassName("kotlin.collections", "Set"), elementType)
    }
    
    /**
     * Convenience function to create Map<K, V> type.
     */
    fun mapOf(keyType: TypeName, valueType: TypeName): ParameterizedTypeName {
        return parameterizedType(ClassName("kotlin.collections", "Map"), keyType, valueType)
    }
}


