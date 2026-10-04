package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD

private val XSD_DOUBLE_NUMBER = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

/** Parses the `xsd:double` lexical space; returns null for anything outside it. */
private fun parseXsdDouble(lexical: String): Double? = when (lexical) {
    "INF", "+INF" -> Double.POSITIVE_INFINITY
    "-INF" -> Double.NEGATIVE_INFINITY
    "NaN" -> Double.NaN
    else -> if (XSD_DOUBLE_NUMBER.matches(lexical)) lexical.toDouble() else null
}

/**
 * Result of a SPARQL SELECT query.
 */
interface SparqlQueryResult : Iterable<BindingSet> {
    
    /**
     * Get the number of result rows.
     */
    fun count(): Int
    
    /**
     * Get the first result row, or null if empty.
     */
    fun first(): BindingSet?
    
    /**
     * Get all result rows as a list.
     */
    fun toList(): List<BindingSet>
    
    /**
     * Get result rows as a sequence for streaming.
     */
    fun asSequence(): Sequence<BindingSet>
}

/**
 * Simple in-memory SPARQL query result backed by a list.
 */
class ListSparqlQueryResult(private val rows: List<BindingSet>) : SparqlQueryResult {
    override fun iterator(): Iterator<BindingSet> = rows.iterator()
    override fun count(): Int = rows.size
    override fun first(): BindingSet? = rows.firstOrNull()
    override fun toList(): List<BindingSet> = rows.toList()
    override fun asSequence(): Sequence<BindingSet> = rows.asSequence()
}

/**
 * Binding set backed by a map of variable -> term.
 */
class MapBindingSet(private val values: Map<String, RdfTerm>) : BindingSet {
    override fun get(variable: String): RdfTerm? = values[variable]
    override fun getVariableNames(): Set<String> = values.keys
    override fun hasBinding(variable: String): Boolean = values.containsKey(variable)
}

/**
 * A single row from a SPARQL SELECT query result.
 */
interface BindingSet {
    
    /**
     * Get the value for a variable.
     */
    fun get(variable: String): RdfTerm?
    
    /**
     * Get all variable names in this binding set.
     */
    fun getVariableNames(): Set<String>
    
    /**
     * Check if a variable is bound.
     */
    fun hasBinding(variable: String): Boolean
    
    /**
     * Get a string value for a variable.
     */
    fun getString(variable: String): String? = (get(variable) as? Literal)?.lexical
    
    /**
     * Get an integer value for a variable.
     */
    fun getInt(variable: String): Int? = getString(variable)?.toIntOrNull()
    
    /**
     * Get a double value for a variable.
     *
     * Accepts the `xsd:double` lexical space: decimal/scientific notation plus `INF`, `+INF`,
     * `-INF` and `NaN`. Java-only spellings such as `Infinity`, hex floats or `1d` return null.
     */
    fun getDouble(variable: String): Double? = getString(variable)?.let(::parseXsdDouble)

    /**
     * Get a boolean value for a variable.
     *
     * Accepts the `xsd:boolean` lexical space, which is case-sensitive: `"true"` / `"1"` and
     * `"false"` / `"0"`. Any other lexical form returns null.
     */
    fun getBoolean(variable: String): Boolean? = when (getString(variable)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    
    /**
     * Get a string value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The string value or the default
     */
    fun getStringOr(variable: String, default: String): String = getString(variable) ?: default
    
    /**
     * Get an integer value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The integer value or the default
     */
    fun getIntOr(variable: String, default: Int): Int = getInt(variable) ?: default
    
    /**
     * Get a double value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The double value or the default
     */
    fun getDoubleOr(variable: String, default: Double): Double = getDouble(variable) ?: default
    
    /**
     * Get a boolean value for a variable, or return the default if not bound.
     * 
     * @param variable The variable name
     * @param default The default value to return if the variable is not bound
     * @return The boolean value or the default
     */
    fun getBooleanOr(variable: String, default: Boolean): Boolean = getBoolean(variable) ?: default
    
    /**
     * Get a string value for a variable, or throw an exception if not bound.
     * 
     * @param variable The variable name
     * @return The string value
     * @throws IllegalArgumentException if the variable is not bound
     */
    fun getStringOrThrow(variable: String): String = 
        getString(variable) ?: throw IllegalArgumentException("Variable '$variable' is not bound")
    
    /**
     * Get an integer value for a variable, or throw an exception if not bound.
     * 
     * @param variable The variable name
     * @return The integer value
     * @throws IllegalArgumentException if the variable is not bound or cannot be converted to Int
     */
    fun getIntOrThrow(variable: String): Int = 
        getInt(variable) ?: throw IllegalArgumentException("Variable '$variable' is not bound or cannot be converted to Int")
}
