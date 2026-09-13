package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SparqlExtensionFunction

/**
 * Registry for SPARQL extension functions.
 *
 * Thread-safe. The SPARQL 1.2 built-ins from [Sparql12BuiltInFunctions] are
 * registered when the registry is first used, so lookups never depend on
 * whether some other code happened to touch [Sparql12BuiltInFunctions] first.
 * Listings are snapshots in registration order.
 */
object SparqlExtensionFunctionRegistry {

    private val lock = Any()
    private val functions = LinkedHashMap<String, SparqlExtensionFunction>()

    init {
        Sparql12BuiltInFunctions.functions.forEach { functions[it.iri] = it }
    }

    /**
     * Register a SPARQL extension function. A function with the same IRI is replaced.
     */
    fun register(function: SparqlExtensionFunction) {
        synchronized(lock) { functions[function.iri] = function }
    }

    /**
     * Get all registered functions.
     */
    fun getAllFunctions(): List<SparqlExtensionFunction> = snapshot()

    /**
     * Get function by IRI.
     */
    fun getFunction(iri: String): SparqlExtensionFunction? = synchronized(lock) { functions[iri] }

    /**
     * Get functions by name.
     */
    fun getFunctionsByName(name: String): List<SparqlExtensionFunction> {
        return snapshot().filter { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Check if a function is registered.
     */
    fun isRegistered(iri: String): Boolean = synchronized(lock) { functions.containsKey(iri) }

    /**
     * Get built-in functions only.
     */
    fun getBuiltInFunctions(): List<SparqlExtensionFunction> {
        return snapshot().filter { it.isBuiltIn }
    }

    /**
     * Get custom functions only.
     */
    fun getCustomFunctions(): List<SparqlExtensionFunction> {
        return snapshot().filter { !it.isBuiltIn }
    }

    private fun snapshot(): List<SparqlExtensionFunction> = synchronized(lock) { functions.values.toList() }
}
