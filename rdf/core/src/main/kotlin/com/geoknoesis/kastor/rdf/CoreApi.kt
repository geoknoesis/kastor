package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Minimal, explicit core API contract for engine-agnostic repositories.
 * 
 * **Relationship with [RdfRepository]:**
 * - [SparqlRepository] is the minimal, query-focused interface (SPARQL operations only)
 * - [RdfRepository] extends [SparqlRepository] and adds graph management operations (named graphs, editing, etc.)
 * 
 * Use [SparqlRepository] when you only need SPARQL query capabilities.
 * Use [RdfRepository] when you need full graph management (named graphs, editing, etc.).
 * 
 * All [RdfRepository] implementations also implement [SparqlRepository], so you can use either interface
 * depending on your needs.
 */
interface SparqlRepository : Closeable {
    val defaultGraph: RdfGraph

    fun graph(name: Iri): RdfGraph

    fun select(query: SparqlSelect): SparqlQueryResult
    fun ask(query: SparqlAsk): Boolean
    fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    fun describe(query: SparqlDescribe): Sequence<RdfTriple>
    fun update(query: UpdateQuery)
}

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










