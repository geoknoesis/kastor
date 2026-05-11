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
     * Turtle (RDF 1.2) - human-readable RDF syntax. The Kastor renderer emits
     * RDF 1.2 syntax for triple terms (`<<( s p o )>>`) and directional
     * language strings (`"text"@lang--ltr`); both Jena and RDF4J parsers
     * accept the older RDF-star variants too, so legacy input still parses.
     *
     * Aliases: "TURTLE", "TTL", "TURTLE-1.2", "TURTLE12", "TURTLESTAR".
     */
    TURTLE("TURTLE", "TTL", "TURTLE-1.2", "TURTLE12", "TURTLESTAR"),

    /**
     * JSON-LD format - JSON-based RDF serialization.
     *
     * Aliases: "JSON-LD", "JSONLD", "JSON-LD-1.2", "JSONLD12".
     */
    JSON_LD("JSON-LD", "JSONLD", "JSON-LD-1.2", "JSONLD12"),

    /**
     * RDF/XML format - XML-based RDF serialization.
     *
     * Aliases: "RDF/XML", "RDFXML", "XML".
     */
    RDF_XML("RDF/XML", "RDFXML", "XML"),

    /**
     * N-Triples (RDF 1.2) - simple line-based RDF syntax.
     *
     * Aliases: "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2", "NTRIPLES12".
     */
    N_TRIPLES("N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2", "NTRIPLES12"),

    /**
     * TriG (RDF 1.2) - Turtle syntax extended for RDF datasets (named graphs).
     *
     * Aliases: "TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR".
     */
    TRIG("TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR"),

    /**
     * N-Quads (RDF 1.2) - N-Triples extended with named-graph context.
     *
     * Aliases: "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12".
     */
    N_QUADS("N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12");
    
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
                ?: throw RdfFormatException.Generic(
                    "Unsupported RDF format: $formatString. Supported formats: ${values().joinToString { it.formatName }}",
                    RdfErrorCode.FORMAT_UNSUPPORTED
                )
        }
    }
}

