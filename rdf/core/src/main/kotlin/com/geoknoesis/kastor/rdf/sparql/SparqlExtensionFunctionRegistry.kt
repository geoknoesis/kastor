package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SparqlExtensionFunction

/**
 * Registry for SPARQL extension functions.
 */
object SparqlExtensionFunctionRegistry {
    
    private val functions = mutableMapOf<String, SparqlExtensionFunction>()
    
    /**
     * Register a SPARQL extension function.
     */
    fun register(function: SparqlExtensionFunction) {
        functions[function.iri] = function
    }
    
    /**
     * Get all registered functions.
     */
    fun getAllFunctions(): List<SparqlExtensionFunction> = functions.values.toList()
    
    /**
     * Get function by IRI.
     */
    fun getFunction(iri: String): SparqlExtensionFunction? = functions[iri]
    
    /**
     * Get functions by name.
     */
    fun getFunctionsByName(name: String): List<SparqlExtensionFunction> {
        return functions.values.filter { it.name.equals(name, ignoreCase = true) }
    }
    
    /**
     * Check if a function is registered.
     */
    fun isRegistered(iri: String): Boolean = functions.containsKey(iri)
    
    /**
     * Get built-in functions only.
     */
    fun getBuiltInFunctions(): List<SparqlExtensionFunction> {
        return functions.values.filter { it.isBuiltIn }
    }
    
    /**
     * Get custom functions only.
     */
    fun getCustomFunctions(): List<SparqlExtensionFunction> {
        return functions.values.filter { !it.isBuiltIn }
    }
}









