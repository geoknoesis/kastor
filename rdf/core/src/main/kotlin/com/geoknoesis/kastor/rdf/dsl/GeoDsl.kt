package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.vocab.GEO

/**
 * [GeoSPARQL](https://opengeospatial.github.io/ogc-geosparql/geosparql11/spec.html) geometry linkage helpers.
 */
fun TripleDsl.geo(block: GeoTripleBuilder.() -> Unit) {
    GeoTripleBuilder(triples).apply(block)
}

fun GraphDsl.geo(block: GeoTripleBuilder.() -> Unit) {
    GeoTripleBuilder(triples).apply(block)
}

class GeoTripleBuilder(private val out: MutableList<RdfTriple>) {

    infix fun RdfResource.hasGeometry(g: RdfResource) {
        out.add(RdfTriple(this, GEO.hasGeometry, g))
    }

    infix fun RdfResource.defaultGeometry(g: RdfResource) {
        out.add(RdfTriple(this, GEO.defaultGeometry, g))
    }

    /** [GEO.asWkt] literal with datatype [GEO.wktLiteral]. */
    fun RdfResource.asWkt(wkt: String) {
        out.add(RdfTriple(this, GEO.asWkt, lit(wkt, GEO.wktLiteral)))
    }

    fun RdfResource.asGml(gml: String) {
        out.add(RdfTriple(this, GEO.asGml, lit(gml, GEO.gmlLiteral)))
    }

    infix fun RdfResource.sfEquals(other: RdfResource) {
        out.add(RdfTriple(this, GEO.sfEquals, other))
    }

    infix fun RdfResource.sfWithin(other: RdfResource) {
        out.add(RdfTriple(this, GEO.sfWithin, other))
    }

    infix fun RdfResource.sfContains(other: RdfResource) {
        out.add(RdfTriple(this, GEO.sfContains, other))
    }

    infix fun RdfResource.sfIntersects(other: RdfResource) {
        out.add(RdfTriple(this, GEO.sfIntersects, other))
    }
}
