package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Dublin Core Terms helpers for [TripleDsl] and [GraphDsl] (see [DCTERMS]).
 */
fun TripleDsl.dcterms(block: DctermsTripleBuilder.() -> Unit) {
    DctermsTripleBuilder(triples).apply(block)
}

fun GraphDsl.dcterms(block: DctermsTripleBuilder.() -> Unit) {
    DctermsTripleBuilder(triples).apply(block)
}

class DctermsTripleBuilder(private val out: MutableList<RdfTriple>) {

    fun RdfResource.title(text: String, language: String? = null) {
        out.add(RdfTriple(this, DCTERMS.title, literalFor(text, language)))
    }

    fun RdfResource.description(text: String, language: String? = null) {
        out.add(RdfTriple(this, DCTERMS.description, literalFor(text, language)))
    }

    fun RdfResource.abstractText(text: String, language: String? = null) {
        out.add(RdfTriple(this, DCTERMS.abstract, literalFor(text, language)))
    }

    fun RdfResource.alternative(text: String, language: String? = null) {
        out.add(RdfTriple(this, DCTERMS.alternative, literalFor(text, language)))
    }

    infix fun RdfResource.creator(agent: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.creator, agent))
    }

    infix fun RdfResource.publisher(agent: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.publisher, agent))
    }

    infix fun RdfResource.license(term: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.license, term))
    }

    fun RdfResource.license(uri: String) {
        out.add(RdfTriple(this, DCTERMS.license, string(uri)))
    }

    fun RdfResource.identifier(id: String) {
        out.add(RdfTriple(this, DCTERMS.identifier, string(id)))
    }

    fun RdfResource.issuedLexical(isoDate: String) {
        out.add(RdfTriple(this, DCTERMS.issued, lit(isoDate, XSD.date)))
    }

    fun RdfResource.modifiedLexical(isoDate: String) {
        out.add(RdfTriple(this, DCTERMS.modified, lit(isoDate, XSD.date)))
    }

    fun RdfResource.createdLexical(isoDate: String) {
        out.add(RdfTriple(this, DCTERMS.created, lit(isoDate, XSD.date)))
    }

    infix fun RdfResource.dcType(type: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.type, type))
    }

    fun RdfResource.language(tag: String) {
        out.add(RdfTriple(this, DCTERMS.language, string(tag)))
    }

    infix fun RdfResource.dcSubject(term: RdfTerm) {
        out.add(RdfTriple(this, DCTERMS.subject, term))
    }

    infix fun RdfResource.spatial(loc: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.spatial, loc))
    }

    infix fun RdfResource.temporal(interval: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.temporal, interval))
    }

    infix fun RdfResource.rightsHolder(agent: RdfResource) {
        out.add(RdfTriple(this, DCTERMS.rightsHolder, agent))
    }

    private fun literalFor(text: String, language: String?): RdfTerm =
        if (language != null) lang(text, language) else string(text)
}
