package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/** The checks and datasets of a [SandboxedSelectQuery] and of the nested queries of its custom functions on RDF4J. */
internal object Rdf4jQuerySandbox {
    /**
     * Refuses a [SandboxedSelectQuery] whose query, [parsed] by RDF4J's own SPARQL parser (the one that prepares
     * it), contains `SERVICE`, with [IllegalArgumentException], and returns the parsed query. RDF4J resolves functions
     * by IRI from its registry and never loads classes by name, so `SERVICE` is the only way such a query reaches
     * outside the repository.
     */
    fun check(parsed: org.eclipse.rdf4j.query.parser.ParsedQuery): org.eclipse.rdf4j.query.parser.ParsedQuery {
        parsed.tupleExpr.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
            override fun meet(node: org.eclipse.rdf4j.query.algebra.Service) {
                val ref = node.serviceRef
                throw IllegalArgumentException("A sandboxed query may not use SERVICE ${ref.value?.stringValue()?.let { "<$it>" } ?: "?" + ref.name}")
            }
        })
        return parsed
    }

    /**
     * A nested query of a custom function, prepared as [Rdf4jRepository.withSelectRows] prepares a sandboxed query with
     * bindings ([check] parses and checks its text), on the dataset of its calling query ([caller]; null for Kastor's
     * dataset, [kastorDataset] of the query's algebra). Its own dataset clauses select among the named graphs of [caller]
     * ([within]).
     */
    fun prepareNested(
        query: SandboxedSelectQuery,
        bindings: Map<String, RdfTerm>,
        caller: org.eclipse.rdf4j.query.Dataset?,
        check: (String) -> org.eclipse.rdf4j.query.parser.ParsedQuery,
        kastorDataset: (org.eclipse.rdf4j.query.algebra.TupleExpr) -> org.eclipse.rdf4j.query.Dataset?,
    ): Rdf4jNestedQuery {
        val bound = Rdf4jInitialBindings.bind(query.sparql, bindings)
        val parsed = check(bound.sparql)
        val placeholders = org.eclipse.rdf4j.query.impl.MapBindingSet()
        bound.placeholders.forEach { (name, value) -> placeholders.addBinding(name, value) }
        val own = parsed.dataset
        val dataset = when {
            caller == null -> own ?: kastorDataset(parsed.tupleExpr)
            own == null -> caller
            else -> within(own, caller)
        }
        return Rdf4jNestedQuery(parsed, dataset, if (caller == null && own == null) null else dataset, placeholders, bound.hidden)
    }

    /**
     * The graphs [own] selects among the named graphs of [caller], as Jena's dynamic dataset over the calling query's
     * dataset selects them: any other graph reads as empty.
     */
    fun within(own: org.eclipse.rdf4j.query.Dataset, caller: org.eclipse.rdf4j.query.Dataset): org.eclipse.rdf4j.query.Dataset {
        val dataset = org.eclipse.rdf4j.query.impl.SimpleDataset()
        own.defaultGraphs.filter { it in caller.namedGraphs }.forEach(dataset::addDefaultGraph)
        own.namedGraphs.filter { it in caller.namedGraphs }.forEach(dataset::addNamedGraph)
        // RDF4J reads every context for a dataset without any graph: a graph name that no store holds reads nothing.
        if (dataset.defaultGraphs.isEmpty() && dataset.namedGraphs.isEmpty()) {
            dataset.addDefaultGraph(SimpleValueFactory.getInstance().createIRI("urn:kastor:no-graph:" + java.util.UUID.randomUUID()))
        }
        return dataset
    }

    /** [now] plus [timeout] (at least 1 ns); [Long.MAX_VALUE] (no deadline) when that does not fit in a Long. */
    fun deadlineAfter(now: Long, timeout: java.time.Duration): Long = try {
        Math.addExact(now, timeout.toNanos().coerceAtLeast(1)).let { if (it == Long.MAX_VALUE) it - 1 else it }
    } catch (e: ArithmeticException) {
        Long.MAX_VALUE
    }
}
