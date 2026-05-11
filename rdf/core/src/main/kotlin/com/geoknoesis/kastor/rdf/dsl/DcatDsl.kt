package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.toLiteral
import com.geoknoesis.kastor.rdf.vocab.DCAT

/**
 * [DCAT](https://www.w3.org/TR/vocab-dcat/) catalog / dataset / distribution helpers for [TripleDsl] and [GraphDsl].
 */
fun TripleDsl.dcat(block: DcatTripleBuilder.() -> Unit) {
    DcatTripleBuilder(triples).apply(block)
}

fun GraphDsl.dcat(block: DcatTripleBuilder.() -> Unit) {
    DcatTripleBuilder(triples).apply(block)
}

class DcatTripleBuilder(private val out: MutableList<RdfTriple>) {

    /** [DCAT.datasetProp] from catalog to dataset. */
    infix fun RdfResource.dataset(ds: RdfResource) {
        out.add(RdfTriple(this, DCAT.datasetProp, ds))
    }

    /** [DCAT.distributionProp] from dataset to distribution. */
    infix fun RdfResource.distribution(dist: RdfResource) {
        out.add(RdfTriple(this, DCAT.distributionProp, dist))
    }

    /** [DCAT.catalogProp] from dataset to catalog. */
    infix fun RdfResource.inCatalog(catalog: RdfResource) {
        out.add(RdfTriple(this, DCAT.catalogProp, catalog))
    }

    /** [DCAT.record] on catalog. */
    infix fun RdfResource.catalogRecord(rec: RdfResource) {
        out.add(RdfTriple(this, DCAT.record, rec))
    }

    /** [DCAT.service] on catalog. */
    infix fun RdfResource.service(svc: RdfResource) {
        out.add(RdfTriple(this, DCAT.service, svc))
    }

    /** [DCAT.servesDataset] from data service to dataset. */
    infix fun RdfResource.servesDataset(ds: RdfResource) {
        out.add(RdfTriple(this, DCAT.servesDataset, ds))
    }

    fun RdfResource.accessURL(url: String) {
        out.add(RdfTriple(this, DCAT.accessURL, string(url)))
    }

    fun RdfResource.downloadURL(url: String) {
        out.add(RdfTriple(this, DCAT.downloadURL, string(url)))
    }

    fun RdfResource.mediaType(mt: String) {
        out.add(RdfTriple(this, DCAT.mediaType, string(mt)))
    }

    fun RdfResource.byteSize(bytes: Long) {
        out.add(RdfTriple(this, DCAT.byteSize, bytes.toLiteral()))
    }

    fun RdfResource.keyword(text: String, language: String? = null) {
        out.add(RdfTriple(this, DCAT.keyword, literalFor(text, language)))
    }

    fun RdfResource.landingPage(url: String) {
        out.add(RdfTriple(this, DCAT.landingPage, string(url)))
    }

    infix fun RdfResource.theme(concept: RdfResource) {
        out.add(RdfTriple(this, DCAT.theme, concept))
    }

    fun RdfResource.endpointURL(url: String) {
        out.add(RdfTriple(this, DCAT.endpointURL, string(url)))
    }

    fun RdfResource.endpointDescription(url: String) {
        out.add(RdfTriple(this, DCAT.endpointDescription, string(url)))
    }

    private fun literalFor(text: String, language: String?): RdfTerm =
        if (language != null) lang(text, language) else string(text)
}
