package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.GEO
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.PROV
import com.geoknoesis.kastor.rdf.vocab.SKOS
import com.geoknoesis.kastor.rdf.vocab.TIME
import com.geoknoesis.kastor.rdf.vocab.VOID

/**
 * Container for multiple individual values using curly braces syntax.
 */
class MultipleIndividualValues(val values: List<RdfTerm>)

/**
 * Container for RDF list values using parentheses syntax.
 */
class RdfListValues(val values: List<RdfTerm>)

/**
 * Container for RDF Bag values.
 */
class RdfBagValues(val values: List<RdfTerm>)

/**
 * Container for RDF Seq values.
 */
class RdfSeqValues(val values: List<RdfTerm>)

/**
 * Container for RDF Alt values.
 */
class RdfAltValues(val values: List<RdfTerm>)

/**
 * Container for RDF-star embedded triples.
 */
class RdfStarTriple(val subject: RdfResource, val predicate: Iri, val obj: RdfTerm)

/**
 * Create multiple individual values using curly braces syntax: {value1, value2, value3}
 * Creates individual triples for each value.
 */
fun values(vararg values: RdfTerm): MultipleIndividualValues {
    return MultipleIndividualValues(values.toList())
}

fun values(): MultipleIndividualValues = MultipleIndividualValues(emptyList())

fun values(vararg values: String): MultipleIndividualValues =
    MultipleIndividualValues(values.map { string(it) })

fun values(vararg values: Int): MultipleIndividualValues =
    MultipleIndividualValues(values.map { it.toLiteral() })

fun values(vararg values: Long): MultipleIndividualValues =
    MultipleIndividualValues(values.map { it.toLiteral() })

fun values(vararg values: Double): MultipleIndividualValues =
    MultipleIndividualValues(values.map { it.toLiteral() })

fun values(vararg values: Float): MultipleIndividualValues =
    MultipleIndividualValues(values.map { it.toLiteral() })

fun values(vararg values: Boolean): MultipleIndividualValues =
    MultipleIndividualValues(values.map { it.toLiteral() })


/**
 * Create RDF list values using parentheses syntax: (value1, value2, value3)
 * Creates proper RDF List structure.
 */
fun list(vararg values: RdfTerm): RdfListValues {
    return RdfListValues(values.toList())
}

fun list(): RdfListValues = RdfListValues(emptyList())

fun list(vararg values: String): RdfListValues =
    RdfListValues(values.map { string(it) })

fun list(vararg values: Int): RdfListValues =
    RdfListValues(values.map { it.toLiteral() })

fun list(vararg values: Long): RdfListValues =
    RdfListValues(values.map { it.toLiteral() })

fun list(vararg values: Double): RdfListValues =
    RdfListValues(values.map { it.toLiteral() })

fun list(vararg values: Float): RdfListValues =
    RdfListValues(values.map { it.toLiteral() })

fun list(vararg values: Boolean): RdfListValues =
    RdfListValues(values.map { it.toLiteral() })



/**
 * Create RDF Bag values: bag(value1, value2, value3)
 * Creates rdf:Bag container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun bag(vararg values: RdfTerm): RdfBagValues {
    return RdfBagValues(values.toList())
}

fun bag(): RdfBagValues = RdfBagValues(emptyList())

fun bag(vararg values: String): RdfBagValues =
    RdfBagValues(values.map { string(it) })

fun bag(vararg values: Int): RdfBagValues =
    RdfBagValues(values.map { it.toLiteral() })

fun bag(vararg values: Long): RdfBagValues =
    RdfBagValues(values.map { it.toLiteral() })

fun bag(vararg values: Double): RdfBagValues =
    RdfBagValues(values.map { it.toLiteral() })

fun bag(vararg values: Float): RdfBagValues =
    RdfBagValues(values.map { it.toLiteral() })

fun bag(vararg values: Boolean): RdfBagValues =
    RdfBagValues(values.map { it.toLiteral() })


/**
 * Create RDF Seq values: seq(value1, value2, value3)
 * Creates rdf:Seq container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun seq(vararg values: RdfTerm): RdfSeqValues {
    return RdfSeqValues(values.toList())
}

fun seq(): RdfSeqValues = RdfSeqValues(emptyList())

fun seq(vararg values: String): RdfSeqValues =
    RdfSeqValues(values.map { string(it) })

fun seq(vararg values: Int): RdfSeqValues =
    RdfSeqValues(values.map { it.toLiteral() })

fun seq(vararg values: Long): RdfSeqValues =
    RdfSeqValues(values.map { it.toLiteral() })

fun seq(vararg values: Double): RdfSeqValues =
    RdfSeqValues(values.map { it.toLiteral() })

fun seq(vararg values: Float): RdfSeqValues =
    RdfSeqValues(values.map { it.toLiteral() })

fun seq(vararg values: Boolean): RdfSeqValues =
    RdfSeqValues(values.map { it.toLiteral() })


/**
 * Create RDF Alt values: alt(value1, value2, value3)
 * Creates rdf:Alt container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun alt(vararg values: RdfTerm): RdfAltValues {
    return RdfAltValues(values.toList())
}

fun alt(): RdfAltValues = RdfAltValues(emptyList())

fun alt(vararg values: String): RdfAltValues =
    RdfAltValues(values.map { string(it) })

fun alt(vararg values: Int): RdfAltValues =
    RdfAltValues(values.map { it.toLiteral() })

fun alt(vararg values: Long): RdfAltValues =
    RdfAltValues(values.map { it.toLiteral() })

fun alt(vararg values: Double): RdfAltValues =
    RdfAltValues(values.map { it.toLiteral() })

fun alt(vararg values: Float): RdfAltValues =
    RdfAltValues(values.map { it.toLiteral() })

fun alt(vararg values: Boolean): RdfAltValues =
    RdfAltValues(values.map { it.toLiteral() })


/**
 * Create an embedded triple structure (RDF 1.2 triple term: `<<( s p o )>>`).
 *
 * In RDF 1.2 a triple term may only appear as the **object** of another triple
 * (it is not a resource and cannot be a subject). The DSL helpers that consume
 * this struct emit it accordingly.
 */
fun embedded(subject: RdfResource, predicate: Iri, obj: RdfTerm): RdfStarTriple {
    return RdfStarTriple(subject, predicate, obj)
}


/**
 * Elegant DSL for creating RDF triples.
 * Supports multiple syntax styles for maximum developer productivity.
 *
 * The syntax is defined by [TripleBuilderDsl]; triples collected here are added to a
 * repository or graph by `add { }` / `addToGraph(name) { }`.
 */
class TripleDsl : TripleBuilderDsl<TripleDsl>()

/**
 * Helper class for minus operator syntax.
 */
data class SubjectPredicateChain(val subject: RdfResource, val predicate: Iri)









