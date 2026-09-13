package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.shacl.ImportConfig
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphAccessException

internal object OwlImportsExpander {

    fun expand(root: RdfGraph, cfg: ImportConfig, auxiliary: Map<Iri, RdfGraph>, maxTriples: Long = Long.MAX_VALUE, budget: ValidationBudget = ValidationBudget.NONE): RdfGraph {
        budget.check("shape imports")
        if (!cfg.resolveOwlImports) return root
        val acc = LinkedHashSet<RdfTriple>()
        val visited = mutableSetOf<Iri>()
        val queue = ArrayDeque<Pair<RdfGraph, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            budget.check("shape imports")
            val (g, depth) = queue.removeFirst()
            require(g.size().toLong() <= maxTriples) { "Imported shapes exceed triple budget" }
            val triples = budget.snapshot(g, "shape imports")
            triples.forEach {
                budget.check("shape imports")
                acc.add(it)
                require(acc.size.toLong() <= maxTriples) { "Expanded shapes exceed triple budget" }
            }
            if (depth >= cfg.maxImportDepth) continue
            for (t in triples) {
                budget.check("shape imports")
                if (t.predicate != OWL.imports || t.obj !is Iri) continue
                val imp = t.obj as Iri
                if (!visited.add(imp)) continue
                val next =
                    auxiliary[imp]
                        ?: if (cfg.allowImportFetch) {
                            throw ShapesGraphAccessException(
                                "owl:imports <$imp> could not be resolved offline (network fetch not implemented)",
                            )
                        } else {
                            continue
                        }
                queue.addLast(next to depth + 1)
            }
        }
        return graphFromTriples(acc, budget)
    }
}
