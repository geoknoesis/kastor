package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.shacl.ImportConfig
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphAccessException

internal object OwlImportsExpander {

    fun expand(root: RdfGraph, cfg: ImportConfig, auxiliary: Map<Iri, RdfGraph>): RdfGraph {
        if (!cfg.resolveOwlImports) return root
        val acc = LinkedHashSet<RdfTriple>()
        fun walk(g: RdfGraph, chain: Set<Iri>, depth: Int) {
            if (depth > cfg.maxImportDepth) return
            acc.addAll(g.getTriples())
            for (t in g.getTriples()) {
                if (t.predicate != OWL.imports || t.obj !is Iri) continue
                val imp = t.obj as Iri
                if (imp in chain) continue // cycle on this import branch
                val next =
                    auxiliary[imp]
                        ?: if (cfg.allowImportFetch) {
                            throw ShapesGraphAccessException(
                                "owl:imports <$imp> could not be resolved offline (network fetch not implemented)",
                            )
                        } else {
                            continue
                        }
                walk(next, chain + imp, depth + 1)
            }
        }
        walk(root, emptySet(), 0)
        return graphFromTriples(acc)
    }
}
