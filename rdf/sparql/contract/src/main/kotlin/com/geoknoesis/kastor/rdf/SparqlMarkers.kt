package com.geoknoesis.kastor.rdf

/**
 * Lightweight SPARQL query markers (string payloads and sealed variants).
 *
 * Kept in `:rdf:sparql-contract` so `:rdf:core` and providers can share types
 * without pulling in the SPARQL AST/DSL (`:rdf:sparql-lang`).
 */
interface SparqlQuery {
    val sparql: String
}

interface SparqlSelect : SparqlQuery
interface SparqlAsk : SparqlQuery
interface SparqlConstruct : SparqlQuery
interface SparqlDescribe : SparqlQuery

@JvmInline
value class SparqlSelectQuery(override val sparql: String) : SparqlSelect

@JvmInline
value class SparqlAskQuery(override val sparql: String) : SparqlAsk

@JvmInline
value class SparqlConstructQuery(override val sparql: String) : SparqlConstruct

@JvmInline
value class SparqlDescribeQuery(override val sparql: String) : SparqlDescribe

@JvmInline
value class UpdateQuery(val sparql: String)
