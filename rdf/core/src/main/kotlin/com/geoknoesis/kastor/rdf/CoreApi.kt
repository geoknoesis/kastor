package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Read-only SPARQL query interface.
 * 
 * Provides query operations (SELECT, ASK, CONSTRUCT, DESCRIBE) without mutation capabilities.
 * This is the base interface for read-only query operations.
 * 
 * **Relationship with other interfaces:**
 * - [SparqlQueryable] provides read-only query operations
 * - [SparqlMutable] extends [SparqlQueryable] and adds [update] for mutations
 * - [Dataset] extends [SparqlQueryable] and represents a SPARQL dataset (read-only, multiple graphs)
 * - [RdfRepository] extends [Dataset] and [SparqlMutable] for full repository capabilities
 * 
 * Use [SparqlQueryable] when you only need read-only SPARQL query capabilities.
 * Use [SparqlMutable] when you need update operations.
 * Use [Dataset] when you need to work with SPARQL datasets (multiple default/named graphs).
 * Use [RdfRepository] when you need full repository capabilities (queries, updates, graph management).
 */
interface SparqlQueryable : Closeable {
    val defaultGraph: RdfGraph

    fun graph(name: Iri): RdfGraph

    fun select(query: SparqlSelect): SparqlQueryResult
    fun ask(query: SparqlAsk): Boolean
    fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    fun describe(query: SparqlDescribe): Sequence<RdfTriple>
}

/**
 * Mutable SPARQL interface that adds update operations.
 * 
 * Extends [SparqlQueryable] with SPARQL UPDATE capabilities.
 * This interface separates read-only query operations from mutable update operations,
 * following the Interface Segregation Principle.
 * 
 * **Note:** The concrete class [com.geoknoesis.kastor.rdf.sparql.SparqlRepository] in the
 * `rdf/sparql` package represents a remote SPARQL endpoint implementation.
 */
interface SparqlMutable : SparqlQueryable {
    fun update(query: UpdateQuery)
}

/**
 * @deprecated Use [SparqlQueryable] for read-only queries or [SparqlMutable] for mutable operations.
 * This type alias is provided for backward compatibility only.
 */
@Deprecated(
    message = "Use SparqlQueryable for read-only queries or SparqlMutable for mutable operations",
    replaceWith = ReplaceWith("SparqlQueryable"),
    level = DeprecationLevel.WARNING
)
typealias SparqlRepository = SparqlQueryable

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










