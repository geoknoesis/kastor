package com.geoknoesis.kastor.rdf

/**
 * RDF serialization formats supported by Kastor.
 * 
 * This enum provides type-safe format specification for serialization and parsing operations.
 * Formats are case-insensitive when specified as strings, but using this enum provides
 * compile-time safety and better IDE support.
 * 
 * **Usage:**
 * ```kotlin
 * val turtle = graph.serialize(RdfFormat.TURTLE)
 * val graph = Rdf.parse(data, RdfFormat.JSON_LD)
 * ```
 * 
 * **Format Aliases:**
 * - `TURTLE` / `TTL` - Turtle format
 * - `JSON_LD` / `JSONLD` - JSON-LD format
 * - `RDF_XML` / `RDFXML` / `XML` - RDF/XML format
 * - `N_TRIPLES` / `NT` / `NTRIPLES` - N-Triples format
 */
enum class RdfFormat(val formatName: String, vararg val aliases: String) {
    /**
     * Turtle format - human-readable RDF syntax.
     * 
     * Aliases: "TURTLE", "TTL"
     */
    TURTLE("TURTLE", "TTL"),
    
    /**
     * JSON-LD format - JSON-based RDF serialization.
     * 
     * Aliases: "JSON-LD", "JSONLD"
     */
    JSON_LD("JSON-LD", "JSONLD"),
    
    /**
     * RDF/XML format - XML-based RDF serialization.
     * 
     * Aliases: "RDF/XML", "RDFXML", "XML"
     */
    RDF_XML("RDF/XML", "RDFXML", "XML"),
    
    /**
     * N-Triples format - simple line-based RDF syntax.
     * 
     * Aliases: "N-TRIPLES", "NT", "NTRIPLES"
     */
    N_TRIPLES("N-TRIPLES", "NT", "NTRIPLES"),
    
    /**
     * TriG format - Turtle syntax extended for RDF datasets (named graphs).
     * Supports serialization of multiple named graphs in a single document.
     * 
     * Aliases: "TRIG", "TRI-G"
     */
    TRIG("TRIG", "TRI-G"),
    
    /**
     * N-Quads format - N-Triples syntax extended for RDF datasets (named graphs).
     * Each line contains subject, predicate, object, and graph name.
     * 
     * Aliases: "N-QUADS", "NQUADS", "NQ"
     */
    N_QUADS("N-QUADS", "NQUADS", "NQ");
    
    companion object {
        /**
         * Standard RDF graph formats (single graph).
         */
        val GRAPH_FORMATS = listOf(TURTLE, JSON_LD, RDF_XML, N_TRIPLES)
        
        /**
         * Standard RDF quad formats (datasets with named graphs).
         */
        val QUAD_FORMATS = listOf(TRIG, N_QUADS)
        
        /**
         * All standard RDF formats that are commonly supported.
         */
        val STANDARD_FORMATS = GRAPH_FORMATS + QUAD_FORMATS
        
        /**
         * Check if a format is a quad format (supports named graphs).
         */
        fun isQuadFormat(format: RdfFormat): Boolean = format in QUAD_FORMATS
        
        /**
         * Check if a format string represents a quad format.
         */
        fun isQuadFormat(formatString: String): Boolean {
            val format = fromString(formatString) ?: return false
            return isQuadFormat(format)
        }
        
        /**
         * Parse a format string (case-insensitive) to an RdfFormat enum value.
         * 
         * @param formatString The format string (e.g., "TURTLE", "turtle", "TTL")
         * @return The corresponding RdfFormat, or null if not recognized
         */
        fun fromString(formatString: String): RdfFormat? {
            val normalized = formatString.uppercase().trim()
            return values().firstOrNull { format ->
                format.formatName.uppercase() == normalized ||
                format.aliases.any { it.uppercase() == normalized }
            }
        }
        
        /**
         * Parse a format string (case-insensitive) to an RdfFormat enum value.
         * 
         * @param formatString The format string (e.g., "TURTLE", "turtle", "TTL")
         * @return The corresponding RdfFormat
         * @throws RdfFormatException if the format is not recognized
         */
        fun fromStringOrThrow(formatString: String): RdfFormat {
            return fromString(formatString)
                ?: throw RdfFormatException("Unsupported RDF format: $formatString. Supported formats: ${values().joinToString { it.formatName }}")
        }
    }
}

