@file:JvmName("QueryTerms")

package com.geoknoesis.kastor.rdf

/**
 * SPARQL Query Terms API - Core types and DSL entry points.
 * 
 * This module provides:
 * - Core SPARQL types (Var, PrefixDeclaration, VersionDeclaration)
 * - Query DSL entry points that delegate to the new AST-based DSL
 * 
 * The new DSL is located in the `sparql` package and provides a more robust,
 * Kotlin-idiomatic way to build SPARQL queries inspired by Jena SSE.
 */

// ============================================================================
// CORE TYPES (kept for backward compatibility with existing code)
// ============================================================================

/**
 * Represents a SPARQL prefix declaration.
 */
data class PrefixDeclaration(val prefix: String, val namespace: String) {
    init {
        require(prefix.isNotBlank()) { "Prefix must not be blank" }
        require(namespace.isNotBlank()) { "Namespace must not be blank" }
    }
    
    override fun toString(): String = "PREFIX $prefix: <$namespace>"
}

/**
 * Represents a SPARQL 1.2 VERSION declaration.
 */
data class VersionDeclaration(val version: String) {
    init {
        require(version.isNotBlank()) { "Version must not be blank" }
        require(version.matches(Regex("\\d+\\.\\d+"))) { "Version must be in format X.Y" }
    }
    
    override fun toString(): String = "VERSION $version"
}

/**
 * Represents a SPARQL variable.
 */
data class Var(val name: String) : RdfTerm {
    init { 
        require(name.isNotBlank()) { "Variable name must not be blank" }
        require(name[0].isLetterOrDigit()) { "Variable name must start with letter or digit" }
    }
    override fun toString(): String = "?$name"
}

/**
 * Common SPARQL prefixes for popular vocabularies.
 */
object CommonPrefixes {
    const val FOAF = "http://xmlns.com/foaf/0.1/"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    const val OWL = "http://www.w3.org/2002/07/owl#"
    const val XSD = "http://www.w3.org/2001/XMLSchema#"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val DCTERMS = "http://purl.org/dc/terms/"
    const val SCHEMA = "https://schema.org/"
    const val DBPEDIA = "http://dbpedia.org/ontology/"
    const val WIKIDATA = "http://www.wikidata.org/entity/"
    const val SKOS = "http://www.w3.org/2004/02/skos/core#"
    const val PROV = "http://www.w3.org/ns/prov#"
    const val DCAT = "http://www.w3.org/ns/dcat#"
    const val VOID = "http://rdfs.org/ns/void#"
    const val GEO = "http://www.opengis.net/ont/geosparql#"
    const val TIME = "http://www.w3.org/2006/time#"
}

// ============================================================================
// VARIABLE CREATION FUNCTIONS
// ============================================================================

/**
 * Creates a SPARQL variable.
 */
fun var_(name: String) = Var(name)

/**
 * Creates a SPARQL variable (shorter alias).
 */
fun `var`(name: String) = Var(name)

// SPARQL query DSL lives in `:rdf:sparql-lang` (`com.geoknoesis.kastor.rdf.sparql`).
// `addCommonPrefixes` for SelectBuilder lives in that module next to SelectBuilder.
