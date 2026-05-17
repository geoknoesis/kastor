package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple

internal fun mergeGraphs(first: RdfGraph, second: RdfGraph): RdfGraph =
    Rdf.graph {
        first.getTriples().forEach { t -> t.subject - t.predicate - t.obj }
        second.getTriples().forEach { t -> t.subject - t.predicate - t.obj }
    }

internal fun mergeGraphsAll(graphs: List<RdfGraph>): RdfGraph =
    Rdf.graph {
        graphs.forEach { g -> g.getTriples().forEach { t -> t.subject - t.predicate - t.obj } }
    }

internal fun graphFromTriples(triples: Collection<RdfTriple>): RdfGraph =
    Rdf.graph {
        triples.forEach { t -> t.subject - t.predicate - t.obj }
    }
