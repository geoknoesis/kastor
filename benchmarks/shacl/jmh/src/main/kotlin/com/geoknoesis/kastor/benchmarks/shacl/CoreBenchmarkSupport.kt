package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL

object CoreBenchmarkSupport {
    @JvmStatic fun graph(size: Int): RdfGraph = MemoryGraph((0 until size).map {
        RdfTriple(Iri("urn:subject:$it"), Iri("urn:predicate"), string("value:$it"))
    })
    @JvmStatic fun symmetric(size: Int, prefix: String): RdfGraph = MemoryGraph((0 until size).map {
        RdfTriple(BlankNode("$prefix$it"), Iri("urn:predicate"), string("identical"))
    })
    @JvmStatic fun lookup(graph: RdfGraph, size: Int): Int =
        graph.find(Iri("urn:subject:${size / 2}"), Iri("urn:predicate"), null).size

    @JvmStatic fun validationData(size: Int): RdfGraph = MemoryGraph(buildList {
        repeat(size) { add(RdfTriple(Iri("urn:subject:$it"), RDF.type, Iri("urn:class:${it % 100}"))) }
        for (i in 1 until 100) add(RdfTriple(Iri("urn:class:$i"), RDFS.subClassOf, Iri("urn:class:${i - 1}")))
    })
    @JvmStatic fun validationShapes(): RdfGraph = MemoryGraph(listOf(
        RdfTriple(Iri("urn:shape"), RDF.type, SHACL.NodeShape),
        RdfTriple(Iri("urn:shape"), SHACL.targetClass, Iri("urn:class:0")),
        RdfTriple(Iri("urn:shape"), SHACL.nodeKind, SHACL.IRI),
    ))
}
