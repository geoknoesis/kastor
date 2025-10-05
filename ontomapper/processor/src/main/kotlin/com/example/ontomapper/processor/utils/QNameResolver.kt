package com.example.ontomapper.processor.utils

/**
 * Utility class for resolving QNames (qualified names) to full IRIs using prefix mappings.
 */
object QNameResolver {

    /**
     * Resolves a QName to a full IRI using the provided prefix mappings.
     * 
     * @param qname The QName to resolve (e.g., "dcat:Catalog")
     * @param prefixMappings Map of prefix names to namespace URIs
     * @return The full IRI (e.g., "http://www.w3.org/ns/dcat#Catalog")
     * @throws IllegalArgumentException if the QName cannot be resolved
     */
    fun resolveQName(qname: String, prefixMappings: Map<String, String>): String {
        if (isQName(qname)) {
            val (prefix, localName) = qname.split(":", limit = 2)
            val namespace = prefixMappings[prefix]
                ?: throw IllegalArgumentException("Unknown prefix: $prefix in QName: $qname")
            return namespace + localName
        }
        // If not a QName, assume it's already a full IRI
        return qname
    }

    /**
     * Checks if a string is a QName (contains a colon and is not a full IRI).
     * 
     * @param iri The string to check
     * @return true if it appears to be a QName, false otherwise
     */
    fun isQName(iri: String): Boolean {
        if (!iri.contains(":")) return false
        
        // If it starts with http:// or https://, it's likely a full IRI
        if (iri.startsWith("http://") || iri.startsWith("https://")) return false
        
        // If it starts with other common IRI schemes, it's likely a full IRI
        if (iri.startsWith("urn:") || iri.startsWith("file:") || iri.startsWith("data:")) return false
        
        // If it starts with colon or ends with colon, it's not a valid QName
        if (iri.startsWith(":") || iri.endsWith(":")) return false
        
        return true
    }

    /**
     * Extracts prefix mappings from a list of Prefix annotations.
     * 
     * @param prefixAnnotations List of Prefix annotations
     * @return Map of prefix names to namespace URIs
     */
    fun extractPrefixMappings(prefixAnnotations: List<Any>): Map<String, String> {
        return prefixAnnotations.associate { prefixAnnotation ->
            // Extract name and namespace from annotation
            val name = extractAnnotationValue(prefixAnnotation, "name") as? String
                ?: throw IllegalArgumentException("Prefix annotation missing 'name' property")
            val namespace = extractAnnotationValue(prefixAnnotation, "namespace") as? String
                ?: throw IllegalArgumentException("Prefix annotation missing 'namespace' property")
            name to namespace
        }
    }

    /**
     * Extracts a value from an annotation by property name.
     * This is a helper method for working with annotation instances.
     */
    private fun extractAnnotationValue(annotation: Any, propertyName: String): Any? {
        // This is a simplified implementation
        // In a real implementation, you would use reflection or annotation processing APIs
        return try {
            val method = annotation.javaClass.getMethod(propertyName)
            method.invoke(annotation)
        } catch (e: Exception) {
            null
        }
    }
}
