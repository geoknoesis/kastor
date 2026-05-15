package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfTerm
import org.apache.jena.rdf.model.RDFNode

/**
 * Converts a Jena [RDFNode] to a Kastor [RdfTerm], including RDF 1.2 triple terms and
 * directional language literals.
 */
fun rdfTermFromJena(node: RDFNode): RdfTerm = JenaTerms.fromNode(node)
