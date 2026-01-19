package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Minimal, explicit core API contract for engine-agnostic repositories.
 * This coexists with [RdfRepository] to allow a staged migration.
 */
interface Repository : Closeable {
    val defaultGraph: RdfGraph

    fun graph(name: Iri): RdfGraph

    fun select(query: SparqlSelect): QueryResult
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










