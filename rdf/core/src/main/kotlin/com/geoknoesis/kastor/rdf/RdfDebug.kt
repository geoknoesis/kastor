package com.geoknoesis.kastor.rdf

import org.slf4j.LoggerFactory

/**
 * Debug mode configuration for Kastor RDF operations.
 * 
 * Provides debugging capabilities for:
 * - **Prefix expansion**: Log QName to IRI resolution
 * - **Query tracing**: Log SPARQL queries with execution details
 * 
 * **Usage:**
 * ```kotlin
 * // Enable debug mode
 * RdfDebug.enable {
 *     showPrefixExpansion = true
 *     showQueryTrace = true
 * }
 * 
 * // Or configure individually
 * RdfDebug.showPrefixExpansion = true
 * RdfDebug.showQueryTrace = true
 * 
 * // Disable debug mode
 * RdfDebug.disable()
 * ```
 * 
 * **Logging:**
 * Debug output is logged using SLF4J at the `DEBUG` level.
 * Ensure your logging configuration includes the `com.geoknoesis.kastor.rdf` logger.
 * 
 * **Example log4j2.xml:**
 * ```xml
 * <Logger name="com.geoknoesis.kastor.rdf" level="DEBUG"/>
 * ```
 */
object RdfDebug {
    
    private val logger = LoggerFactory.getLogger(RdfDebug::class.java)
    
    /**
     * Whether to log prefix expansion (QName to IRI resolution).
     * Default: false
     */
    var showPrefixExpansion: Boolean = false
        private set
    
    /**
     * Whether to log SPARQL query execution details.
     * Default: false
     */
    var showQueryTrace: Boolean = false
        private set
    
    /**
     * Whether debug mode is enabled (at least one debug option is enabled).
     */
    val isEnabled: Boolean
        get() = showPrefixExpansion || showQueryTrace
    
    /**
     * Enable debug mode with configuration.
     * 
     * @param configure Configuration block for debug options
     */
    fun enable(configure: Config.() -> Unit = {}) {
        val config = Config().apply(configure)
        showPrefixExpansion = config.showPrefixExpansion
        showQueryTrace = config.showQueryTrace
        
        if (isEnabled) {
            logger.debug("RdfDebug enabled: prefixExpansion=$showPrefixExpansion, queryTrace=$showQueryTrace")
        }
    }
    
    /**
     * Disable all debug options.
     */
    fun disable() {
        showPrefixExpansion = false
        showQueryTrace = false
        logger.debug("RdfDebug disabled")
    }
    
    /**
     * Configuration builder for debug options.
     */
    class Config {
        /**
         * Whether to log prefix expansion (QName to IRI resolution).
         */
        var showPrefixExpansion: Boolean = false
        
        /**
         * Whether to log SPARQL query execution details.
         */
        var showQueryTrace: Boolean = false
    }
    
    /**
     * Log prefix expansion (QName to IRI resolution).
     * 
     * @param qname The QName being resolved (e.g., "foaf:name")
     * @param iri The resolved IRI (e.g., "http://xmlns.com/foaf/0.1/name")
     * @param prefixMappings The prefix mappings used for resolution
     */
    fun logPrefixExpansion(qname: String, iri: String, prefixMappings: Map<String, String>) {
        if (showPrefixExpansion) {
            logger.debug("Prefix expansion: '$qname' → '$iri' (prefix mappings: $prefixMappings)")
        }
    }
    
    /**
     * Log SPARQL query execution.
     * 
     * @param queryType The type of query (SELECT, ASK, CONSTRUCT, DESCRIBE, UPDATE)
     * @param query The SPARQL query string
     * @param bindings Optional variable bindings (for parameterized queries)
     * @param executionTimeMs Optional query execution time in milliseconds
     * @param resultCount Optional result count (for SELECT queries)
     */
    fun logQueryTrace(
        queryType: String,
        query: String,
        bindings: Map<String, RdfTerm>? = null,
        executionTimeMs: Long? = null,
        resultCount: Int? = null
    ) {
        if (showQueryTrace) {
            val parts = mutableListOf<String>()
            parts.add("Query trace: $queryType")
            parts.add("Query: $query")
            
            if (bindings != null && bindings.isNotEmpty()) {
                parts.add("Bindings: $bindings")
            }
            
            if (executionTimeMs != null) {
                parts.add("Execution time: ${executionTimeMs}ms")
            }
            
            if (resultCount != null) {
                parts.add("Result count: $resultCount")
            }
            
            logger.debug(parts.joinToString(" | "))
        }
    }
    
    /**
     * Log SPARQL query error.
     * 
     * @param queryType The type of query
     * @param query The SPARQL query string
     * @param error The error message
     */
    fun logQueryError(queryType: String, query: String, error: String) {
        if (showQueryTrace) {
            logger.debug("Query error: $queryType | Query: $query | Error: $error")
        }
    }
}

