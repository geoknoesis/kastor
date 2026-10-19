package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.asGraphRef
import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphNotFoundException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
import com.geoknoesis.kastor.rdf.shacl.native.OwlImportsExpander
import com.geoknoesis.kastor.rdf.shacl.native.ShapesGraphTriplesCollector

/** Admission, namespaces, imports and discovery for one shapes snapshot. */
internal class NativeShapesPreparation(private val config: ValidationConfig) {
    fun prepare(
        data: RdfGraph,
        shapesArg: RdfGraph,
        datasetForDiscovery: Dataset?,
        budget: ValidationBudget,
        importWarnings: MutableCollection<String>,
    ): List<RdfTriple> {
        val aux = config.dataset.auxiliaryGraphs
        val auxiliarySize = aux.values.fold(0L) { n, g -> budget.check("auxiliary admission"); Math.addExact(n, g.size().toLong()) }
        if (auxiliarySize > config.maxCombinedGraphTriples) throw ShaclValidationException("Auxiliary shapes exceed maxCombinedGraphTriples")
        val ds = datasetForDiscovery ?: config.dataset.validationDataset
        val primaryGraph: RdfGraph =
            when (val name = config.dataset.shapesGraphNamedGraph) {
                null -> shapesArg
                else -> {
                    val g =
                        ds?.getNamedGraph(name)
                            ?: aux[name]
                            ?: throw ShapesGraphNotFoundException(
                                "dataset.shapesGraphNamedGraph <$name> not found in validationDataset or auxiliaryGraphs",
                            )
                    g
                }
            }
        val resolveImports = config.imports.resolveOwlImports
        val discoverShapes = config.dataset.discoverShapesGraphFromData
        if (primaryGraph.size().toLong() > config.maxCombinedGraphTriples) {
            throw ShaclValidationException("Primary shapes exceed maxCombinedGraphTriples")
        }
        // One namespace table across imports and discovery: separate tables would reuse generated labels.
        val scopes = if (resolveImports || discoverShapes) {
            val source = primaryGraph.asGraphRef().sourceRepository
            // Preserve the identities of related shapes from the same repository, even for nodes absent from
            // the primary shapes. This also keeps imported targets/hasValue terms shared with repository data.
            val related = if (source == null) emptyList() else buildList {
                addAll(aux.values.filter { it.asGraphRef().sourceRepository === source })
                if (discoverShapes) {
                    val visited = mutableSetOf<Iri>()
                    var discoveredSize = 0L
                    data.find(predicate = SHACL.shapesGraph).forEach { triple ->
                        budget.check("shape namespaces")
                        val name = triple.obj as? Iri ?: return@forEach
                        if (!visited.add(name)) return@forEach
                        val graph = ds?.getNamedGraph(name) ?: aux[name] ?: return@forEach
                        if (graph.asGraphRef().sourceRepository === source) {
                            val size = graph.size().toLong()
                            if (size > config.maxCombinedGraphTriples - discoveredSize) {
                                throw ShaclValidationException("Discovered shapes exceed maxCombinedGraphTriples")
                            }
                            discoveredSize += size
                            add(graph)
                        }
                    }
                }
            }
            related.forEach {
                budget.check("shape namespaces")
                if (it.size().toLong() > config.maxCombinedGraphTriples) {
                    throw ShaclValidationException("Related shapes exceed maxCombinedGraphTriples")
                }
            }
            val anchor = if (related.isNotEmpty()) {
                Dataset { defaultGraph(primaryGraph); related.forEach { defaultGraph(it) } }.defaultGraph.asGraphRef(source)
            } else primaryGraph
            com.geoknoesis.kastor.rdf.BlankNodeScopes(anchor)
        } else null
        budget.check("shape namespaces")
        val expanded =
            if (resolveImports) {
                budget.snapshot(
                    OwlImportsExpander.expand(
                        primaryGraph, config.imports, aux, config.maxCombinedGraphTriples, budget, importWarnings, scopes,
                    ),
                    "expanded shapes",
                )
            } else {
                budget.snapshot(primaryGraph, "primary shapes")
            }
        val extra =
            if (discoverShapes) {
                ShapesGraphTriplesCollector.collectFromData(data, ds, aux, config.maxCombinedGraphTriples, budget, scopes!!)
            } else {
                emptyList()
            }
        // Fast path: a single graph snapshot is already duplicate-free, so no merge copy is needed.
        if (extra.isEmpty()) return expanded
        val unique = linkedSetOf<RdfTriple>()
        for (t in expanded) { budget.tick("shape merge"); unique.add(t) }
        for (t in extra) { budget.tick("shape merge"); unique.add(t) }
        return unique.toList()
    }

}
