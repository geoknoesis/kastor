package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge

/**
 * Blank-node-aware RDF graph isomorphism using Apache Jena's matcher
 * (W3C RDF Concepts–style structural equivalence).
 *
 * Both graphs are compared through detached copies of what [RdfGraph.getTriples] exposes (so
 * inference views of repository graphs are included, and store models are never touched); the copies
 * are always closed.
 *
 * Add module `com.geoknoesis.kastor:rdf-testkit` to your test source set together with
 * a concrete RDF provider (`rdf-jena` is pulled transitively by this module).
 */
object RdfGraphIsomorphism {

    /** True if [expected] and [actual] contain the same RDF up to blank node relabelling. */
    fun isIsomorphic(expected: RdfGraph, actual: RdfGraph): Boolean {
        val left = JenaBridge.copyToJenaModel(expected)
        try {
            val right = JenaBridge.copyToJenaModel(actual)
            try {
                return left.isIsomorphicWith(right)
            } finally {
                right.close()
            }
        } finally {
            left.close()
        }
    }
}
