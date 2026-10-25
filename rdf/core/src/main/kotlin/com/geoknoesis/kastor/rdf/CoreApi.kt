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
 * - [SparqlMutable] extends [SparqlQueryable] and adds `update` for mutations
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

    /**
     * Execute a SPARQL SELECT query.
     *
     * @throws RdfQueryException if the query fails to parse or execute
     */
    @Throws(RdfException::class)
    fun select(query: SparqlSelect): SparqlQueryResult
    /** Consume rows inside their resource scope; do not retain the sequence. */
    @Throws(RdfException::class)
    fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T =
        consume(select(query).asSequence())
    /**
     * Execute with real initial bindings and a provider-enforced query timeout.
     *
     * **Optional operation.** The default implementation throws [UnsupportedOperationException], and so do the
     * graph-only `memory` provider and a [Dataset] view that has to copy its graphs for a query (a view of the graphs of
     * one SPARQL-capable repository runs it). The bundled Jena and RDF4J repositories implement it; check before
     * relying on it with another provider.
     *
     * Every provider that implements it applies the same contract, SPARQL substitution semantics: each bound variable behaves as if its
     * value were written in the query in its place (restricting the query before aggregation, LIMIT and FILTER), a
     * bound variable listed in the projection is bound in every row, `SELECT *` does not return bound variables, and
     * `BOUND(?v)` is true. A query that is not a SELECT, that assigns a bound variable (`BIND(... AS ?v)`,
     * `(expr AS ?v)`, `VALUES ?v`), or that uses it inside a sub-select that does not project it is rejected with
     * [IllegalArgumentException]. This holds for every kind of term, including blank nodes, triple terms and
     * directional language strings.
     *
     * @throws UnsupportedOperationException if this implementation does not support bound, timed queries
     */
    @Throws(RdfException::class)
    fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T =
        throw UnsupportedOperationException("This provider does not support bound, timed queries")
    /** Consume graph results inside their resource scope; do not retain the sequence. */
    @Throws(RdfException::class)
    fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T =
        consume(construct(query))
    /** Execute a SPARQL ASK query. @throws RdfQueryException if the query fails to parse or execute */
    @Throws(RdfException::class)
    fun ask(query: SparqlAsk): Boolean
    /** Execute a SPARQL CONSTRUCT query. @throws RdfQueryException if the query fails to parse or execute */
    @Throws(RdfException::class)
    fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    /** Execute a SPARQL DESCRIBE query. @throws RdfQueryException if the query fails to parse or execute */
    @Throws(RdfException::class)
    fun describe(query: SparqlDescribe): Sequence<RdfTriple>

    /**
     * Release the resources of this object. Redeclared without the `IOException` of [Closeable.close], so that Java
     * can use it in try-with-resources without a `catch (IOException e)`.
     */
    override fun close()
}

/**
 * Mutable SPARQL interface that adds update operations.
 * 
 * Extends [SparqlQueryable] with SPARQL UPDATE capabilities.
 * This interface separates read-only query operations from mutable update operations,
 * following the Interface Segregation Principle.
 * 
 * **Note:** The concrete class `com.geoknoesis.kastor.rdf.sparql.SparqlRepository` in the
 * `rdf/sparql` package represents a remote SPARQL endpoint implementation.
 */
interface SparqlMutable : SparqlQueryable {
    /** Execute a SPARQL UPDATE operation. @throws RdfQueryException if the update fails to parse or execute */
    @Throws(RdfException::class)
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
