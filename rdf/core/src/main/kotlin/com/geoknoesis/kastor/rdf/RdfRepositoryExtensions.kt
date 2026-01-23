package com.geoknoesis.kastor.rdf

import java.io.InputStream

/**
 * Provider-agnostic extensions for RDF repository (dataset) operations.
 * 
 * These extensions support quad formats (TriG, N-Quads) that can serialize
 * and parse datasets with multiple named graphs.
 */

/**
 * Serializes this repository (dataset) to the specified quad format.
 * 
 * Quad formats (TriG, N-Quads) support serialization of multiple named graphs,
 * including the default graph and all named graphs in the repository.
 * 
 * **Format Support:**
 * - The method automatically discovers providers that support the requested format
 * - If multiple providers support the format, the first available one is used
 * - Format names are case-insensitive
 * 
 * **Example:**
 * ```kotlin
 * val repo = Rdf.memory()
 * // ... add data to default graph and named graphs ...
 * 
 * // Using string format
 * val trig = repo.serializeDataset("TRIG")
 * val nquads = repo.serializeDataset("N-QUADS")
 * 
 * // Using enum format (type-safe)
 * val trig2 = repo.serializeDataset(RdfFormat.TRIG)
 * ```
 * 
 * @param format The RDF quad format (string or RdfFormat enum)
 * @return The serialized RDF dataset as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 * @throws IllegalArgumentException if the format is not a quad format
 */
fun RdfRepository.serializeDataset(format: String): String {
    val formatEnum = RdfFormat.fromStringOrThrow(format)
    if (!RdfFormat.isQuadFormat(formatEnum)) {
        throw IllegalArgumentException("Format '${formatEnum.formatName}' is not a quad format. Use serializeGraph() for graph formats, or use TRIG or N-QUADS for datasets.")
    }
    return serializeDataset(formatEnum)
}

/**
 * Serializes this repository (dataset) to the specified quad format.
 * 
 * Type-safe version using [RdfFormat] enum.
 * 
 * @param format The RDF quad format enum value (TRIG or N_QUADS)
 * @return The serialized RDF dataset as a string
 * @throws RdfFormatException if no provider supports the format or serialization fails
 * @throws IllegalArgumentException if the format is not a quad format
 */
fun RdfRepository.serializeDataset(format: RdfFormat): String {
    if (!RdfFormat.isQuadFormat(format)) {
        throw IllegalArgumentException("Format '${format.formatName}' is not a quad format. Use serializeGraph() for graph formats, or use TRIG or N-QUADS for datasets.")
    }
    
    val providers = RdfProviderRegistry.discoverProviders()
    
    // Try to find a provider that supports this format
    for (provider in providers) {
        if (provider.supportsFormat(format.formatName)) {
            try {
                return provider.serializeDataset(this, format.formatName)
            } catch (e: UnsupportedOperationException) {
                // Provider doesn't actually support it, try next
                continue
            } catch (e: RdfFormatException) {
                // Format error, rethrow
                throw e
            } catch (e: Exception) {
                // Other error, wrap and throw
                throw RdfFormatException(
                    "Failed to serialize dataset to ${format.formatName} using provider ${provider.id}: ${e.message}",
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
