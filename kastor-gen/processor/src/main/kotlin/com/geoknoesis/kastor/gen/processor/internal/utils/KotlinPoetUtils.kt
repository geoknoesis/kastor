package com.geoknoesis.kastor.gen.processor.internal.utils

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/**
 * Utility functions for working with KotlinPoet.
 *
 * Provides a small, readable API for building parameterized types over
 * KotlinPoet's public `parameterizedBy` extension.
 */
internal object KotlinPoetUtils {

    /**
     * Creates a parameterized type (e.g., List<String>, MutableList<RdfResource>)
     * using KotlinPoet's public API (no private-constructor reflection, so it is
     * stable across KotlinPoet versions).
     *
     * @param rawType The base type (e.g., List, MutableList, Set, Map)
     * @param typeArguments The type arguments (e.g., String, RdfResource)
     * @return A ParameterizedTypeName representing the parameterized type
     */
    fun parameterizedType(rawType: ClassName, vararg typeArguments: TypeName): ParameterizedTypeName =
        rawType.parameterizedBy(*typeArguments)
    
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


