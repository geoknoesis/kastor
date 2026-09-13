package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphNotFoundException

internal object ShapesGraphTriplesCollector {

    /**
     * Collect triples from graphs referenced via `sh:shapesGraph` on the data graph (architecture §9.2).
     */
    fun collectFromData(data: RdfGraph, dataset: Dataset?, auxiliary: Map<Iri, RdfGraph>, maxTriples: Long = Long.MAX_VALUE, budget: ValidationBudget = ValidationBudget.NONE): List<RdfTriple> {
        val out = mutableListOf<RdfTriple>()
        val visited = mutableSetOf<Iri>()
        for (t in budget.snapshot(data, "shape discovery")) {
            budget.check("shape discovery")
            if (t.predicate != SHACL.shapesGraph) continue
            val name = t.obj as? Iri ?: continue
            if (!visited.add(name)) continue
            val g =
                dataset?.getNamedGraph(name)
                    ?: auxiliary[name]
                    ?: throw ShapesGraphNotFoundException(
                        "sh:shapesGraph <$name> not found in dataset or ValidationConfig.dataset.auxiliaryGraphs",
                    )
            if (out.size.toLong() + g.size() > maxTriples) throw com.geoknoesis.kastor.rdf.shacl.ShaclValidationException("Discovered shapes exceed maxCombinedGraphTriples")
            budget.snapshot(g, "discovered shapes").forEach { budget.check("discovered shapes"); out.add(it) }
        }
        return out
    }
}
