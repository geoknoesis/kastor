package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * [GeoSPARQL](https://opengeospatial.github.io/ogc-geosparql/geosparql11/spec.html) core RDF vocabulary (`geo:`).
 *
 * @see <a href="http://www.opengis.net/ont/geosparql#">GeoSPARQL ontology namespace</a>
 */
object GEO : Vocabulary {
    override val namespace: String = "http://www.opengis.net/ont/geosparql#"
    override val prefix: String = "geo"

    val Feature: Iri by lazy { term("Feature") }
    val Geometry: Iri by lazy { term("Geometry") }
    val SpatialObject: Iri by lazy { term("SpatialObject") }

    val hasGeometry: Iri by lazy { term("hasGeometry") }
    val defaultGeometry: Iri by lazy { term("defaultGeometry") }
    val asWkt: Iri by lazy { term("asWKT") }
    val asGml: Iri by lazy { term("asGML") }
    val wktLiteral: Iri by lazy { term("wktLiteral") }
    val gmlLiteral: Iri by lazy { term("gmlLiteral") }

    val sfEquals: Iri by lazy { term("sfEquals") }
    val sfDisjoint: Iri by lazy { term("sfDisjoint") }
    val sfIntersects: Iri by lazy { term("sfIntersects") }
    val sfTouches: Iri by lazy { term("sfTouches") }
    val sfCrosses: Iri by lazy { term("sfCrosses") }
    val sfWithin: Iri by lazy { term("sfWithin") }
    val sfContains: Iri by lazy { term("sfContains") }
    val sfOverlaps: Iri by lazy { term("sfOverlaps") }
}
