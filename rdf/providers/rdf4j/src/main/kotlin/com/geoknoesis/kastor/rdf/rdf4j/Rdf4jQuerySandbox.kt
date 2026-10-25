package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/** The checks and datasets of a [SandboxedSelectQuery] and of the nested queries of its custom functions on RDF4J. */
internal object Rdf4jQuerySandbox {

    /** Why a sandboxed query is refused on a store that [evaluatesSandboxed] rejects. */
    const val FOREIGN_STORE: String =
        "This RDF4J repository wraps a store that Kastor did not create (a remote endpoint, or a store without Kastor's " +
            "evaluation strategy), so it cannot be restricted to sandboxed queries; run them on a repository created by " +
            "Rdf4jRepository's factory methods or the provider"

    /**
     * Whether [repository] evaluates queries with [KastorEvaluationStrategyFactory], the only evaluation that holds a
     * [SandboxedSelectQuery] to its sandbox (the allowlist of functions, the custom functions, the refusal of `SERVICE`
     * and the bounds of [Rdf4jEvaluationBounds]): a [org.eclipse.rdf4j.repository.sail.SailRepository], possibly
     * behind repository wrappers, whose base Sail (below any inferencer, SHACL or other stacked Sail) is a memory or
     * native store that the factory methods created. A remote repository (HTTP, SPARQL endpoint) would send the
     * untrusted text to a server that evaluates it with its own functions, and any other store would evaluate it with
     * RDF4J's default strategy and every function of the global registry.
     */
    fun evaluatesSandboxed(repository: org.eclipse.rdf4j.repository.Repository): Boolean {
        val base = generateSequence(repository) { (it as? org.eclipse.rdf4j.repository.DelegatingRepository)?.delegate }.last()
        val sail = (base as? org.eclipse.rdf4j.repository.sail.SailRepository)?.sail ?: return false
        val store = generateSequence(sail) { (it as? org.eclipse.rdf4j.sail.StackableSail)?.baseSail }.last()
        val factory = when (store) {
            is org.eclipse.rdf4j.sail.memory.MemoryStore -> store.evaluationStrategyFactory
            is org.eclipse.rdf4j.sail.nativerdf.NativeStore -> store.evaluationStrategyFactory
            else -> null
        }
        return factory is KastorEvaluationStrategyFactory
    }
    /**
     * Refuses a [SandboxedSelectQuery] whose query, [parsed] by RDF4J's own SPARQL parser (the one that prepares
     * it), contains `SERVICE` or a custom aggregate, with [IllegalArgumentException], and returns the parsed query.
     * RDF4J resolves functions by IRI from its registry and never loads classes by name, so `SERVICE` is the only way
     * such a query reaches outside the repository; a custom aggregate (an `AggregateFunctionCall`, which RDF4J
     * resolves from its global `CustomAggregateFunctionRegistry`) would run whatever code registered it, as Jena's
     * custom aggregates would (both providers refuse them). The evaluation strategy is the second barrier
     * ([refusal]): it fails both, and calls only RDF4J's own built-in functions ([Rdf4jSandboxFunctions]) besides the
     * query's custom ones.
     */
    fun check(parsed: org.eclipse.rdf4j.query.parser.ParsedQuery): org.eclipse.rdf4j.query.parser.ParsedQuery {
        refusal(parsed.tupleExpr)?.let { throw IllegalArgumentException(it) }
        return parsed
    }

    /**
     * Most property-path closures (`*`, `+`) one sandboxed query may have, as Jena bounds the size of a path. RDF4J
     * expands the other path operators into joins and unions, whose rows and statement reads check the deadline.
     */
    const val MAX_PATH_CLOSURES: Int = 64

    /**
     * Most triple-term patterns and expressions (`<< s p o >>`) one sandboxed query may have: RDF4J's join optimizer
     * takes time exponential in the nesting of the joins they create (a chain of 40 `BIND(<< ?t :p ?t >> AS ?u)` kept
     * it busy for minutes while it planned the query, before any deadline check could run).
     */
    const val MAX_TRIPLE_TERMS: Int = 16

    /** Why a sandboxed query whose algebra is [expr] is refused (see [check]); null when it is not. */
    fun refusal(expr: org.eclipse.rdf4j.query.algebra.QueryModelNode): String? {
        var refused: String? = null
        var closures = 0
        var tripleTerms = 0
        expr.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
            override fun meet(node: org.eclipse.rdf4j.query.algebra.Service) {
                val ref = node.serviceRef
                refused = refused ?: "A sandboxed query may not use SERVICE ${ref.value?.stringValue()?.let { "<$it>" } ?: "?" + ref.name}"
            }

            override fun meet(node: org.eclipse.rdf4j.query.algebra.AggregateFunctionCall) {
                refused = refused ?: "A sandboxed query may not use the custom aggregate <${node.iri}>"
            }

            override fun meet(node: org.eclipse.rdf4j.query.algebra.TripleRef) {
                tripleTerm()
                super.meet(node)
            }

            override fun meet(node: org.eclipse.rdf4j.query.algebra.ValueExprTripleRef) {
                tripleTerm()
                super.meet(node)
            }

            fun tripleTerm() {
                if (++tripleTerms > MAX_TRIPLE_TERMS) {
                    refused = refused ?: "A sandboxed query may not use more than $MAX_TRIPLE_TERMS triple-term patterns or expressions"
                }
            }

            override fun meet(node: org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath) {
                if (++closures > MAX_PATH_CLOSURES) {
                    refused = refused ?: "A sandboxed query may not use more than $MAX_PATH_CLOSURES property-path closures (* and +)"
                }
                super.meet(node)
            }
        })
        return refused
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
        // IRIs and literals are written into the text: RDF4J's BindingAssignerOptimizer trips an internal assertion
        // for a natively bound variable in some positions (inside COALESCE), so only what text cannot spell is bound.
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

    /**
     * The checked parses ([check]) of the nested queries of one top-level query, by query text, so that a function
     * called with the same bindings (the same focus node, or none) does not parse and check the same text again. The
     * bound IRIs and literals are part of the text (see [prepareNested]), so calls bound to different values parse
     * their own texts; at most [capacity] texts are kept, the least recently used dropped first.
     *
     * A cached [org.eclipse.rdf4j.query.parser.ParsedQuery] is shared by the calls: they only read it, and evaluate a
     * clone of its algebra.
     */
    class NestedParses(
        private val check: (String) -> org.eclipse.rdf4j.query.parser.ParsedQuery,
        private val capacity: Int = 64,
    ) {
        private val parses = object : LinkedHashMap<String, org.eclipse.rdf4j.query.parser.ParsedQuery>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, org.eclipse.rdf4j.query.parser.ParsedQuery>?) =
                size > capacity
        }

        /** The checked parse of [sparql]; a refused or malformed text fails as [check] does, every time. */
        fun get(sparql: String): org.eclipse.rdf4j.query.parser.ParsedQuery =
            synchronized(parses) { parses[sparql] } ?: check(sparql).also { synchronized(parses) { parses[sparql] = it } }
    }

    /** [now] plus [timeout] (at least 1 ns); [Long.MAX_VALUE] (no deadline) when that does not fit in a Long. */
    fun deadlineAfter(now: Long, timeout: java.time.Duration): Long = try {
        Math.addExact(now, timeout.toNanos().coerceAtLeast(1)).let { if (it == Long.MAX_VALUE) it - 1 else it }
    } catch (e: ArithmeticException) {
        Long.MAX_VALUE
    }
}
