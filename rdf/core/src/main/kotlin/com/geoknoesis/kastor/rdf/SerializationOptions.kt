package com.geoknoesis.kastor.rdf

/**
 * Options for RDF serialization.
 * 
 * Provides control over how RDF data is serialized, including formatting,
 * prefix handling, and format-specific options.
 * 
 * @param prettyPrint Whether to use pretty-printed output (indentation, line breaks)
 * @param baseUri Base URI for resolving relative IRIs
 * @param prefixMappings Custom prefix mappings (e.g., "ex" -> "http://example.org/")
 * @param useAbbreviatedSyntax Whether to use abbreviated syntax when possible (Turtle: `ex:name` vs `<http://example.org/name>`)
 * @param lineWidth Maximum line width for pretty-printed output (0 = no limit)
 * @param jsonLdContext JSON-LD context for compaction (JSON-LD format only)
 * @param jsonLdCompact Whether to compact JSON-LD output (JSON-LD format only)
 * @param jsonLdFrame JSON-LD frame for framing (JSON-LD format only)
 * 
 * @sample com.example.SerializeWithOptions
 */
data class SerializationOptions(
    /**
     * Whether to use pretty-printed output with indentation and line breaks.
     * Default: true for most formats
     */
    val prettyPrint: Boolean = true,
    
    /**
     * Base URI for resolving relative IRIs in the output.
     * If null, no base URI is set.
     */
    val baseUri: String? = null,
    
    /**
     * Custom prefix mappings for the output.
     * Map of prefix name to namespace URI (e.g., "ex" -> "http://example.org/").
     * These are in addition to any prefixes already defined in the graph.
     */
    val prefixMappings: Map<String, String> = emptyMap(),
    
    /**
     * Whether to use abbreviated syntax when possible.
     * For Turtle: use `ex:name` instead of `<http://example.org/name>`.
     * Default: true
     */
    val useAbbreviatedSyntax: Boolean = true,
    
    /**
     * Maximum line width for pretty-printed output.
     * 0 means no limit.
     * Default: 80
     */
    val lineWidth: Int = 80,
    
    /**
     * JSON-LD context for compaction (JSON-LD format only).
     * Can be a URI string or a JSON object/array.
     * If null, no context is applied.
     * 
     * **Note**: Compaction may not preserve all RDF data if the context is incomplete.
     * See [JSON-LD Compaction and Framing Guide](../../../docs/kastor/guides/json-ld-compaction-framing.md) for details.
     */
    val jsonLdContext: String? = null,
    
    /**
     * Whether to compact JSON-LD output (JSON-LD format only).
     * If true, uses the provided or default context to compact the output.
     * Default: false
     * 
     * **Note**: Compaction with a complete context is usually lossless, but may lose
     * information if the context doesn't include all terms. Use expanded JSON-LD
     * (jsonLdCompact = false) if you need to preserve all RDF data.
     */
    val jsonLdCompact: Boolean = false,
    
    /**
     * JSON-LD frame for framing (JSON-LD format only).
     * Can be a URI string or a JSON object.
     * If null, no framing is applied.
     * 
     * **Warning**: Framing may filter out data that doesn't match the frame pattern.
     * Framing is NOT lossless - properties not included in the frame will be lost.
     * Use framing for presentation/API responses, not for data storage or round-trip conversion.
     * See [JSON-LD Compaction and Framing Guide](../../../docs/kastor/guides/json-ld-compaction-framing.md) for details.
     */
    val jsonLdFrame: String? = null
) {
    companion object {
        /**
         * Default serialization options with pretty printing enabled.
         */
        val DEFAULT = SerializationOptions()
        
        /**
         * Compact serialization options (no pretty printing, minimal output).
         */
        val COMPACT = SerializationOptions(
            prettyPrint = false,
            useAbbreviatedSyntax = false,
            lineWidth = 0
        )
        
        /**
         * Pretty-printed serialization options with wide lines.
         */
        val PRETTY = SerializationOptions(
            prettyPrint = true,
            lineWidth = 120
        )
    }
    
    /**
     * Builder for creating [SerializationOptions] with a fluent API.
     */
    class Builder {
        var prettyPrint: Boolean = true
        var baseUri: String? = null
        var prefixMappings: MutableMap<String, String> = mutableMapOf()
        var useAbbreviatedSyntax: Boolean = true
        var lineWidth: Int = 80
        var jsonLdContext: String? = null
        var jsonLdCompact: Boolean = false
        var jsonLdFrame: String? = null
        
        /**
         * Add a prefix mapping.
         */
        fun prefix(name: String, uri: String) {
            prefixMappings[name] = uri
        }
        
        /**
         * Build the [SerializationOptions] instance.
         */
        fun build(): SerializationOptions {
            return SerializationOptions(
                prettyPrint = prettyPrint,
                baseUri = baseUri,
                prefixMappings = prefixMappings,
                useAbbreviatedSyntax = useAbbreviatedSyntax,
                lineWidth = lineWidth,
                jsonLdContext = jsonLdContext,
                jsonLdCompact = jsonLdCompact,
                jsonLdFrame = jsonLdFrame
            )
        }
    }
}

