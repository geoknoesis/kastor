package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.toLiteral
import com.geoknoesis.kastor.rdf.vocab.VOID

/**
 * [VoID](https://www.w3.org/TR/void/) dataset description helpers for [TripleDsl] and [GraphDsl].
 */
fun TripleDsl.voidMeta(block: VoidTripleBuilder.() -> Unit) {
    VoidTripleBuilder(triples).apply(block)
}

fun GraphDsl.voidMeta(block: VoidTripleBuilder.() -> Unit) {
    VoidTripleBuilder(triples).apply(block)
}

class VoidTripleBuilder(private val out: MutableList<RdfTriple>) {

    infix fun RdfResource.voidSubset(other: RdfResource) {
        out.add(RdfTriple(this, VOID.subset, other))
    }

    infix fun RdfResource.voidRoot(res: RdfResource) {
        out.add(RdfTriple(this, VOID.rootResource, res))
    }

    infix fun RdfResource.voidVocabulary(vocab: RdfResource) {
        out.add(RdfTriple(this, VOID.vocabulary, vocab))
    }

    infix fun RdfResource.voidFeature(feat: RdfResource) {
        out.add(RdfTriple(this, VOID.feature, feat))
    }

    fun RdfResource.voidTriples(count: Long) {
        out.add(RdfTriple(this, VOID.triples, count.toLiteral()))
    }

    fun RdfResource.voidClasses(count: Long) {
        out.add(RdfTriple(this, VOID.classes, count.toLiteral()))
    }

    fun RdfResource.voidDistinctSubjects(count: Long) {
        out.add(RdfTriple(this, VOID.distinctSubjects, count.toLiteral()))
    }

    fun RdfResource.voidProperties(count: Long) {
        out.add(RdfTriple(this, VOID.properties, count.toLiteral()))
    }

    fun RdfResource.voidDocuments(count: Long) {
        out.add(RdfTriple(this, VOID.documents, count.toLiteral()))
    }

    fun RdfResource.sparqlEndpoint(url: String) {
        out.add(RdfTriple(this, VOID.sparqlEndpoint, string(url)))
    }

    fun RdfResource.dataDump(url: String) {
        out.add(RdfTriple(this, VOID.dataDump, string(url)))
    }

    fun RdfResource.uriSpace(prefix: String) {
        out.add(RdfTriple(this, VOID.uriSpace, string(prefix)))
    }
}
