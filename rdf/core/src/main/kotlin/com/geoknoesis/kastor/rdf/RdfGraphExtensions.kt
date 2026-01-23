package com.geoknoesis.kastor.rdf

import java.io.InputStream

/**
 * Provider-agnostic extensions for RDF graph operations.
 * 
 * These extensions automatically discover and use available providers
 * that support the requested operations, making the API format-focused
 * rather than provider-specific.
 */

/**
 * Serializes this graph to the specified RDF format.
 * 
 * This method is provider-agnostic and automatically uses an available provider
 * that supports the requested format. The format can be specified as either
 * a string (e.g., "TURTLE", "JSON-LD") or an [RdfFormat] enum value.
 * 
 * **Format Support:**
 * - The method automatically discovers providers that support the requested format
 * - If multiple providers support the format, the first available one is used
 * - Format names are case-insensitive
 * 
 * **Example:**
 * ```kotlin
 * val graph = Rdf.graph { /* ... */ }
 * 
 * // Using string format
 * val turtle = graph.serialize("TURTLE")
 * val jsonld = graph.serialize("JSON-LD")
 * 
 * // Using enum format (type-safe)
 * val turtle2 = graph.serialize(RdfFormat.TURTLE)
 * ```
 * 
 * @param format The RDF format (string or RdfFormat enum)
 * @return The serialized RDF data as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 */
fun RdfGraph.serialize(format: String): String {
    val formatEnum = RdfFormat.fromStringOrThrow(format)
    return serialize(formatEnum)
}

/**
 * Serializes this graph to the specified RDF format.
 * 
 * Type-safe version using [RdfFormat] enum.
 * 
 * @param format The RDF format enum value
 * @return The serialized RDF data as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 */
fun RdfGraph.serialize(format: RdfFormat): String {
    val providers = RdfProviderRegistry.discoverProviders()
    
    // Try to find a provider that supports this format
    for (provider in providers) {
        if (provider.supportsFormat(format.formatName)) {
            try {
                return provider.serializeGraph(this, format.formatName)
            } catch (e: UnsupportedOperationException) {
                // Provider doesn't actually support it, try next
                continue
            } catch (e: RdfFormatException) {
                // Format error, rethrow
                throw e
            } catch (e: Exception) {
                // Other error, wrap and throw
                throw RdfFormatException(
                    "Failed to serialize graph to ${format.formatName} using provider ${provider.id}: ${e.message}",
                    e
                )
            }
        }
    }
    
    throw RdfFormatException(
        "No provider found that supports format: ${format.formatName}. " +
        "Available providers: ${providers.map { it.id }.joinToString()}"
    )
}

