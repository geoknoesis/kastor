package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge

/**
 * Blank-node-aware RDF graph isomorphism using Apache Jena's matcher
 * (W3C RDF Concepts–style structural equivalence).
 *
 * Add module `com.geoknoesis.kastor:rdf-testkit` to your test source set together with
 * a concrete RDF provider (`rdf-jena` is pulled transitively by this module).
 */
object RdfGraphIsomorphism {

    /** True if [expected] and [actual] contain the same RDF up to blank node relabelling. */
    fun isIsomorphic(expected: RdfGraph, actual: RdfGraph): Boolean {
        val left = JenaBridge.toJenaModel(expected)
        try {
            val right = JenaBridge.toJenaModel(actual)
            try {
                return left.isIsomorphicWith(right)
            } finally {
                if (JenaBridge.getJenaModel(actual) == null) right.close()
            }
        } finally {
            if (JenaBridge.getJenaModel(expected) == null) left.close()
        }
    }
}
