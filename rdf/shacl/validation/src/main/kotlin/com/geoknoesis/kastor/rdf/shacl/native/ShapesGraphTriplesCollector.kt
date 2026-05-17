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
    fun collectFromData(data: RdfGraph, dataset: Dataset?, auxiliary: Map<Iri, RdfGraph>): List<RdfTriple> {
        val out = mutableListOf<RdfTriple>()
        for (t in data.getTriples()) {
            if (t.predicate != SHACL.shapesGraph) continue
            val name = t.obj as? Iri ?: continue
            val g =
                dataset?.getNamedGraph(name)
                    ?: auxiliary[name]
                    ?: throw ShapesGraphNotFoundException(
                        "sh:shapesGraph <$name> not found in dataset or ValidationConfig.dataset.auxiliaryGraphs",
                    )
            out.addAll(g.getTriples())
        }
        return out
    }
}
