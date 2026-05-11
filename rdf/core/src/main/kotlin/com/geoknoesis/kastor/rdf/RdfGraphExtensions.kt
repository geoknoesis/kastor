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
fun RdfGraph.serialize(format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
    val formatEnum = RdfFormat.fromStringOrThrow(format)
    return serialize(formatEnum, options)
}

/**
 * Serializes this graph to the specified RDF format with options.
 * 
 * @param format The RDF format string
 * @param configure Options builder lambda
 * @return The serialized RDF data as a string
 */
fun RdfGraph.serialize(
    format: String,
    configure: SerializationOptions.Builder.() -> Unit
): String {
    val formatEnum = RdfFormat.fromStringOrThrow(format)
    return serialize(formatEnum, configure)
}

/**
 * Serializes this graph to the specified RDF format.
 * 
 * Type-safe version using [RdfFormat] enum.
 * 
 * @param format The RDF format enum value
 * @param options Serialization options (optional)
 * @return The serialized RDF data as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 */
fun RdfGraph.serialize(format: RdfFormat, options: SerializationOptions = SerializationOptions.DEFAULT): String {
    val providers = RdfProviderRegistry.discoverProviders()
    
    // Try to find a provider that supports this format
    for (provider in providers) {
        if (provider.supportsFormat(format.formatName)) {
            try {
                return provider.serializeGraph(this, format.formatName, options)
            } catch (e: UnsupportedOperationException) {
                // Provider doesn't actually support it, try next
                continue
            } catch (e: RdfFormatException) {
                // Format error, rethrow
                throw e
            } catch (e: Exception) {
                // Other error, wrap and throw
                throw RdfFormatException.Generic(
                    "Failed to serialize graph to ${format.formatName} using provider ${provider.id}: ${e.message}",
                    RdfErrorCode.FORMAT_SERIALIZATION_ERROR,
                    e
                )
            }
        }
    }
    
    val availableFormats = providers.flatMap { provider ->
        val caps = provider.getCapabilities()
        // Prefer supportedOutputFormats if available, fallback to supportedInputFormats
        if (caps.supportedOutputFormats.isNotEmpty()) {
            caps.supportedOutputFormats
        } else {
            caps.supportedInputFormats
        }
    }.distinct()
    
    throw RdfFormatException.UnsupportedFormat(
        format.formatName,
        availableFormats
    )
}

/**
 * Serializes this graph to the specified RDF format with options.
 * 
 * Convenience method that allows configuring serialization options using a builder lambda.
 * 
 * @param format The RDF format enum value
 * @param configure Options builder lambda
 * @return The serialized RDF data as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 * 
 * @sample com.example.SerializeWithOptionsBuilder
 */
fun RdfGraph.serialize(
    format: RdfFormat,
    configure: SerializationOptions.Builder.() -> Unit
): String {
    val options = SerializationOptions.Builder().apply(configure).build()
    return serialize(format, options)
}

