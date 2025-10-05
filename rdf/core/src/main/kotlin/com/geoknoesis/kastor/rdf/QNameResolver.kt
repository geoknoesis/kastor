package com.geoknoesis.kastor.rdf

/**
 * Utility for resolving QNames (qualified names) to full IRIs using prefix mappings.
 */
internal object QNameResolver {

    /**
     * Resolves a QName or IRI string to a full IRI using the provided prefix mappings.
     * 
     * @param iriOrQName The QName (e.g., "foaf:name") or full IRI
     * @param prefixMappings Map of prefix names to namespace URIs
     * @return The full IRI
     * @throws IllegalArgumentException if the QName cannot be resolved
     */
    fun resolve(iriOrQName: String, prefixMappings: Map<String, String>): String {
        if (isQName(iriOrQName)) {
            val (prefix, localName) = iriOrQName.split(":", limit = 2)
            val namespace = prefixMappings[prefix]
                ?: throw IllegalArgumentException("Unknown prefix: '$prefix' in QName: '$iriOrQName'")
            return namespace + localName
        }
        // If not a QName, return as-is
        return iriOrQName
    }

    /**
     * Checks if a string is a QName (contains a colon and is not a full IRI).
     */
    private fun isQName(iri: String): Boolean {
        if (!iri.contains(":")) return false
        
        // If it starts with http:// or https://, it's a full IRI
        if (iri.startsWith("http://") || iri.startsWith("https://")) return false
        
        // If it starts with other common IRI schemes, it's a full IRI
        if (iri.startsWith("urn:") || iri.startsWith("file:") || iri.startsWith("data:")) return false
        
        // If it starts with colon or ends with colon, it's not a valid QName
        if (iri.startsWith(":") || iri.endsWith(":")) return false
        
        return true
    }
}
