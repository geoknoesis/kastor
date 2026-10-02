package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.shacl.ImportConfig
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphAccessException

/**
 * The `owl:imports` closure of a shapes graph, resolved offline against the supplied graphs ([ImportConfig]).
 *
 * An import is followed when its IRI is a key of `auxiliary`, up to [ImportConfig.maxImportDepth] levels below the
 * root. An import that is not followed is never dropped silently: an IRI without a graph, or one beyond the depth
 * limit, adds a message to `warnings` (the validator reports them as report-level warnings). Exceeding `maxTriples`
 * fails with a [ShaclValidationException], like every other resource limit of a validation run.
 */
internal object OwlImportsExpander {

    fun expand(
        root: RdfGraph,
        cfg: ImportConfig,
        auxiliary: Map<Iri, RdfGraph>,
        maxTriples: Long = Long.MAX_VALUE,
        budget: ValidationBudget = ValidationBudget.NONE,
        warnings: MutableCollection<String>? = null,
    ): RdfGraph {
        budget.check("shape imports")
        if (!cfg.resolveOwlImports) return root
        val acc = LinkedHashSet<RdfTriple>()
        val visited = mutableSetOf<Iri>()
        val queue = ArrayDeque<Pair<RdfGraph, Int>>()
        queue.addLast(root to 0)
        fun overBudget(what: String, size: Long): Nothing = throw ShaclValidationException(
            "$what ($size triples) exceed ValidationConfig.maxCombinedGraphTriples ($maxTriples) while resolving owl:imports",
        )
        while (queue.isNotEmpty()) {
            budget.check("shape imports")
            val (g, depth) = queue.removeFirst()
            if (g.size().toLong() > maxTriples) overBudget("Imported shapes", g.size().toLong())
            val triples = budget.snapshot(g, "shape imports")
            triples.forEach {
                budget.check("shape imports")
                acc.add(it)
                if (acc.size.toLong() > maxTriples) overBudget("The shapes graph and its imports", acc.size.toLong())
            }
            for (t in triples) {
                budget.check("shape imports")
                if (t.predicate != OWL.imports || t.obj !is Iri) continue
                val imp = t.obj as Iri
                if (!visited.add(imp)) continue
                val next = auxiliary[imp]
                @Suppress("DEPRECATION")
                val failOnUnresolved = cfg.allowImportFetch
                when {
                    depth >= cfg.maxImportDepth -> warnings?.add(
                        "owl:imports <${imp.value}> was not followed: it is more than ImportConfig.maxImportDepth " +
                            "(${cfg.maxImportDepth}) levels below the shapes graph; the shapes it would contribute were not " +
                            "validated",
                    )
                    next == null && failOnUnresolved -> throw ShapesGraphAccessException(
                        "owl:imports <$imp> could not be resolved offline (network fetch not implemented)",
                    )
                    next == null -> warnings?.add(
                        "owl:imports <${imp.value}> was not resolved: no graph with this IRI in " +
                            "DatasetValidationConfig.auxiliaryGraphs (imports are resolved offline); the shapes it would " +
                            "contribute were not validated",
                    )
                    else -> queue.addLast(next to depth + 1)
                }
            }
        }
        return graphFromTriples(acc, budget)
    }
}
