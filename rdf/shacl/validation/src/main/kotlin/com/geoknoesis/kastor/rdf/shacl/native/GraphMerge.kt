package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple

internal fun mergeGraphs(first: RdfGraph, second: RdfGraph, budget: ValidationBudget = ValidationBudget.NONE): RdfGraph =
    Rdf.graph {
        budget.snapshot(first, "graph merge").forEach { t -> budget.check("graph merge"); t.subject - t.predicate - t.obj }
        budget.snapshot(second, "graph merge").forEach { t -> budget.check("graph merge"); t.subject - t.predicate - t.obj }
    }

internal fun mergeGraphsAll(graphs: List<RdfGraph>): RdfGraph =
    Rdf.graph {
        graphs.forEach { g -> g.getTriples().forEach { t -> t.subject - t.predicate - t.obj } }
    }

internal fun graphFromTriples(triples: Collection<RdfTriple>, budget: ValidationBudget = ValidationBudget.NONE): RdfGraph =
    Rdf.graph {
        triples.forEach { t -> budget.check("shape graph construction"); t.subject - t.predicate - t.obj }
    }
