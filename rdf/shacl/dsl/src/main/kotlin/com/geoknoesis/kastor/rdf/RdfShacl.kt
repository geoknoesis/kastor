package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.ShaclDsl

/**
 * Build a SHACL shapes graph using the same entry style as [Rdf.graph].
 *
 * Requires the **`rdf-shacl-dsl`** artifact on the classpath (Gradle **`:rdf:shacl-dsl`**;
 * pulls **`sparql-lang`** for SPARQL-shaped constraints).
 */
fun Rdf.shacl(configure: ShaclDsl.() -> Unit): MutableRdfGraph {
    val dsl = ShaclDsl().apply(configure)
    return dsl.build()
}
