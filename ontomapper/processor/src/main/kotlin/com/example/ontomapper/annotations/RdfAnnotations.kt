package com.example.ontomapper.annotations

/**
 * Annotation for declaring prefix mappings that can be used in other annotations.
 * This allows using QNames (qualified names) instead of full IRIs in RdfClass and RdfProperty annotations.
 * 
 * @property prefixes Map of prefix names to namespace URIs
 * 
 * Example:
 * ```kotlin
 * @PrefixMapping(
 *     prefixes = [
 *         "dcat" to "http://www.w3.org/ns/dcat#",
 *         "dcterms" to "http://purl.org/dc/terms/",
 *         "foaf" to "http://xmlns.com/foaf/0.1/"
 *     ]
 * )
 * @RdfClass(iri = "dcat:Catalog")
 * interface Catalog {
 *     @get:RdfProperty(iri = "dcterms:title")
 *     val title: String
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
annotation class PrefixMapping(
    val prefixes: Array<Prefix>
)

/**
 * Represents a single prefix mapping.
 * 
 * @property name The prefix name (e.g., "dcat")
 * @property namespace The namespace URI (e.g., "http://www.w3.org/ns/dcat#")
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Prefix(
    val name: String,
    val namespace: String
)

/**
 * Annotation for marking domain classes that should be backed by RDF.
 * The processor will generate wrapper implementations for these classes.
 * 
 * Supports both full IRIs and QNames when used with @PrefixMapping.
 * 
 * @property iri The IRI or QName of the RDF class
 * 
 * Examples:
 * ```kotlin
 * // Full IRI
 * @RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
 * 
 * // QName with prefix mapping
 * @PrefixMapping(prefixes = [Prefix("dcat", "http://www.w3.org/ns/dcat#")])
 * @RdfClass(iri = "dcat:Catalog")
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfClass(
    val iri: String = ""
)

/**
 * Annotation for marking properties that map to RDF predicates.
 * 
 * Supports both full IRIs and QNames when used with @PrefixMapping.
 * 
 * @property iri The IRI or QName of the RDF property
 * 
 * Examples:
 * ```kotlin
 * // Full IRI
 * @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
 * 
 * // QName with prefix mapping
 * @get:RdfProperty(iri = "dcterms:title")
 * ```
 */
@Target(AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfProperty(
    val iri: String
)












