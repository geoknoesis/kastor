package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.GEO
import com.geoknoesis.kastor.rdf.vocab.PROV
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS
import com.geoknoesis.kastor.rdf.vocab.TIME
import com.geoknoesis.kastor.rdf.vocab.VOID
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Collision-free blank node labels for the DSLs.
 *
 * Providers keep a blank node's label as its identity, and every `repo.add { }` call builds a fresh
 * DSL, so per-instance counters (`b1`, `b2`, ...) would silently merge unrelated structures across
 * calls. Labels combine a random per-JVM run id with a JVM-wide counter instead.
 */
internal object DslBlankNodes {
    private val run: String = java.lang.Long.toString(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE, 36)
    private val counter = AtomicLong()

    fun next(prefix: String = "b"): BlankNode = BlankNode("${prefix}_${run}_${counter.incrementAndGet()}")
}

/** Built-in prefix mappings shared by all DSLs. */
internal fun builtInPrefixes(): MutableMap<String, String> = mutableMapOf(
    "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
    "owl" to "http://www.w3.org/2002/07/owl#",
    "sh" to "http://www.w3.org/ns/shacl#",
    "xsd" to "http://www.w3.org/2001/XMLSchema#",
    "obo" to "http://purl.obolibrary.org/obo/",
    "skos" to SKOS.namespace,
    "prov" to PROV.namespace,
    "dcat" to DCAT.namespace,
    "dcterms" to DCTERMS.namespace,
    "void" to VOID.namespace,
    "geo" to GEO.namespace,
    "time" to TIME.namespace,
)

/**
 * Shared triple-building syntax for [TripleDsl] and [GraphDsl].
 *
 * Supports the minus operator (`person - FOAF.name - "Alice"`), bracket assignment
 * (`person[FOAF.name] = "Alice"`), natural language (`person has FOAF.name with "Alice"`),
 * RDF lists and containers, QName resolution and the RDF 1.2 reifier pattern.
 *
 * @param D the concrete DSL type, used as the receiver of [reifies] blocks
 */
abstract class TripleBuilderDsl<D : TripleBuilderDsl<D>> internal constructor() {
    private val collected = mutableListOf<RdfTriple>()

    /** Read-only view of the triples collected so far. */
    val triples: List<RdfTriple> get() = collected

    /** Mutable sink for vocabulary DSL extensions in this module that emit triples directly. */
    internal val tripleSink: MutableList<RdfTriple> get() = collected

    // Prefix mappings for QName resolution, initialised with built-in prefixes for common vocabularies
    private val prefixMappings = builtInPrefixes()

    @Suppress("UNCHECKED_CAST")
    private val self: D get() = this as D

    // === PREFIX MAPPING CONFIGURATION ===

    /**
     * Configure prefix mappings for QName resolution.
     *
     * Example:
     * ```kotlin
     * repo.add {
     *     prefixes {
     *         put("foaf", "http://xmlns.com/foaf/0.1/")
     *     }
     *     val person = Iri("http://example.org/person")
     *     person - qname("foaf:name") - "Alice"
     * }
     * ```
     */
    fun prefixes(configure: MutableMap<String, String>.() -> Unit) {
        prefixMappings.configure()
    }

    /**
     * Add a single prefix mapping.
     */
    fun prefix(name: String, namespace: String) {
        prefixMappings[name] = namespace
    }

    /**
     * Create an IRI from a QName or full IRI string.
     *
     * Example:
     * ```kotlin
     * val nameIri = qname("foaf:name")  // Resolves to http://xmlns.com/foaf/0.1/name
     * ```
     */
    fun qname(iriOrQName: String): Iri = Iri(QNameResolver.resolve(iriOrQName, prefixMappings))

    // === PRIMARY SYNTAX: MINUS OPERATOR ===

    /**
     * Minus operator syntax: person - FOAF.name - "Alice"
     */
    infix operator fun RdfResource.minus(predicate: Iri): SubjectPredicateChain {
        return SubjectPredicateChain(this, predicate)
    }

    /**
     * Minus operator syntax: person - FOAF.name - "Alice"
     */
    infix operator fun SubjectPredicateChain.minus(value: RdfTerm) {
        collected.add(RdfTriple(subject, predicate, value))
    }

    /**
     * Bracket assignment syntax: person[FOAF.name] = "Alice"
     */
    operator fun RdfResource.set(predicate: Iri, value: RdfTerm) {
        collected.add(RdfTriple(this, predicate, value))
    }

    operator fun RdfResource.set(predicate: Iri, value: String) {
        collected.add(RdfTriple(this, predicate, string(value)))
    }

    operator fun RdfResource.set(predicate: Iri, value: Int) {
        collected.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Long) {
        collected.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Double) {
        collected.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Float) {
        collected.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Boolean) {
        collected.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: RdfResource) {
        collected.add(RdfTriple(this, predicate, value))
    }

    /**
     * Natural language alias for rdf:type: person `is` FOAF.Person
     */
    infix fun RdfResource.`is`(type: RdfResource) {
        collected.add(RdfTriple(this, RDF.type, type))
    }

    /**
     * Natural language has/with syntax: person has FOAF.name with "Alice"
     */
    infix fun RdfResource.has(predicate: Iri): SubjectPredicateChain {
        return SubjectPredicateChain(this, predicate)
    }

    infix fun SubjectPredicateChain.with(value: RdfTerm) {
        collected.add(RdfTriple(subject, predicate, value))
    }

    infix fun SubjectPredicateChain.with(value: String) {
        collected.add(RdfTriple(subject, predicate, string(value)))
    }

    infix fun SubjectPredicateChain.with(value: Int) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Long) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Double) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Float) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Boolean) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: RdfResource) {
        collected.add(RdfTriple(subject, predicate, value))
    }

    /**
     * Convenience overloads for common literal types.
     */
    infix operator fun SubjectPredicateChain.minus(value: String) {
        collected.add(RdfTriple(subject, predicate, string(value)))
    }

    infix operator fun SubjectPredicateChain.minus(value: Int) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Long) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Double) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Float) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Boolean) {
        collected.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    /**
     * Minus operator with RDF list: person - FOAF.knows - list(friend1, friend2, friend3)
     * Creates proper RDF List structure.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfListValues) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.values)))
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<out RdfTerm>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value)) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<String>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, string(value))) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Int>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Long>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Double>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Float>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Boolean>) {
        values.forEach { value -> collected.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: List<out RdfTerm>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values)))
    }

    @JvmName("minusStringList")
    infix operator fun SubjectPredicateChain.minus(values: List<String>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { string(it) })))
    }

    @JvmName("minusIntList")
    infix operator fun SubjectPredicateChain.minus(values: List<Int>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusLongList")
    infix operator fun SubjectPredicateChain.minus(values: List<Long>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusDoubleList")
    infix operator fun SubjectPredicateChain.minus(values: List<Double>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusFloatList")
    infix operator fun SubjectPredicateChain.minus(values: List<Float>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusBooleanList")
    infix operator fun SubjectPredicateChain.minus(values: List<Boolean>) {
        collected.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    infix operator fun SubjectPredicateChain.minus(values: Pair<RdfTerm, RdfTerm>) {
        collected.add(RdfTriple(subject, predicate, values.first))
        collected.add(RdfTriple(subject, predicate, values.second))
    }

    @JvmName("minusStringPair")
    infix operator fun SubjectPredicateChain.minus(values: Pair<String, String>) {
        collected.add(RdfTriple(subject, predicate, string(values.first)))
        collected.add(RdfTriple(subject, predicate, string(values.second)))
    }

    infix operator fun SubjectPredicateChain.minus(values: Triple<RdfTerm, RdfTerm, RdfTerm>) {
        collected.add(RdfTriple(subject, predicate, values.first))
        collected.add(RdfTriple(subject, predicate, values.second))
        collected.add(RdfTriple(subject, predicate, values.third))
    }

    @JvmName("minusStringTriple")
    infix operator fun SubjectPredicateChain.minus(values: Triple<String, String, String>) {
        collected.add(RdfTriple(subject, predicate, string(values.first)))
        collected.add(RdfTriple(subject, predicate, string(values.second)))
        collected.add(RdfTriple(subject, predicate, string(values.third)))
    }

    /**
     * Minus operator with multiple individual values: person - FOAF.knows - values(friend1, friend2)
     * Creates individual triples for each value.
     */
    infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues) {
        values.values.forEach { value ->
            collected.add(RdfTriple(subject, predicate, value))
        }
    }

    /**
     * Minus operator with RDF Bag: person - DCTERMS.subject - bag("Tech", "AI", "RDF")
     * Creates rdf:Bag container with rdf:_1, rdf:_2, rdf:_3, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfBagValues) {
        collected.add(RdfTriple(subject, predicate, createContainer(RDF.Bag, values.values)))
    }

    /**
     * Minus operator with RDF Seq: person - FOAF.knows - seq(friend1, friend2, friend3)
     * Creates rdf:Seq container with rdf:_1, rdf:_2, rdf:_3, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfSeqValues) {
        collected.add(RdfTriple(subject, predicate, createContainer(RDF.Seq, values.values)))
    }

    /**
     * Minus operator with RDF Alt: person - FOAF.mbox - alt("email1@example.com", "email2@example.com")
     * Creates rdf:Alt container with rdf:_1, rdf:_2, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfAltValues) {
        collected.add(RdfTriple(subject, predicate, createContainer(RDF.Alt, values.values)))
    }

    /**
     * Creates an RDF List from Kotlin values.
     */
    private fun createRdfList(values: List<RdfTerm>): RdfTerm {
        if (values.isEmpty()) return RDF.nil

        val listHead = DslBlankNodes.next()
        var currentNode = listHead

        values.forEachIndexed { index, element ->
            collected.add(RdfTriple(currentNode, RDF.first, element))
            if (index < values.size - 1) {
                val nextNode = DslBlankNodes.next()
                collected.add(RdfTriple(currentNode, RDF.rest, nextNode))
                currentNode = nextNode
            } else {
                collected.add(RdfTriple(currentNode, RDF.rest, RDF.nil))
            }
        }

        return listHead
    }

    /**
     * Creates an RDF container (rdf:Bag, rdf:Seq or rdf:Alt) with rdf:_1, rdf:_2, ... members.
     */
    private fun createContainer(type: Iri, values: List<RdfTerm>): RdfTerm {
        val containerNode = DslBlankNodes.next()
        collected.add(RdfTriple(containerNode, RDF.type, type))
        values.forEachIndexed { index, value ->
            val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
            collected.add(RdfTriple(containerNode, memberProperty, value))
        }
        return containerNode
    }

    // === EXPLICIT TRIPLE CREATION ===

    /**
     * Create a triple explicitly.
     */
    fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm) {
        collected.add(RdfTriple(subject, predicate, obj))
    }

    /**
     * Add multiple triples to the DSL.
     */
    fun addTriples(newTriples: Collection<RdfTriple>) {
        collected.addAll(newTriples)
    }

    // === RDF 1.2 LANGUAGE / DIRECTION HELPERS ===

    /**
     * Create a directional language-tagged literal (RDF 1.2,
     * `rdf:dirLangString`).
     *
     * ```kotlin
     * person - FOAF.name - lang("مرحبا", "ar", Direction.RTL)
     * ```
     */
    fun lang(value: String, language: String, direction: Direction): Literal =
        LangString(value, language, direction)

    /**
     * Create a plain language-tagged literal.
     */
    fun lang(value: String, language: String): Literal = LangString(value, language)

    // === RDF 1.2 REIFIER BUILDER ===

    /**
     * Attach metadata to a triple via the RDF 1.2 reifier pattern.
     *
     * Emits `_:r rdf:reifies <<( s p o )>> .` and runs [configure] with `_:r`
     * (the reifier) bound as the subject of further triples added inside the
     * block. The referenced triple is **not** itself asserted - if you want to
     * assert it, call `triple(subject, predicate, obj)` separately.
     *
     * ```kotlin
     * repo.add {
     *     val alice = iri("http://example.org/alice")
     *     val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
     *     triple(claim.subject, claim.predicate, claim.obj)         // assert it
     *     reifies(claim) { reifier ->                               // and annotate it
     *         reifier - iri("http://example.org/certainty") - 0.9
     *     }
     * }
     * ```
     *
     * @return The reifier, in case the caller wants to attach further triples after the block.
     */
    fun reifies(
        triple: RdfTriple,
        reifier: RdfResource = DslBlankNodes.next("r"),
        configure: D.(RdfResource) -> Unit = {},
    ): RdfResource {
        collected.add(RdfTriple(reifier, RDF.reifies, TripleTerm(triple)))
        self.configure(reifier)
        return reifier
    }

    /**
     * Convenience overload: `reifies(subject, predicate, obj) { reifier -> ... }`.
     */
    fun reifies(
        subject: RdfResource,
        predicate: Iri,
        obj: RdfTerm,
        reifier: RdfResource = DslBlankNodes.next("r"),
        configure: D.(RdfResource) -> Unit = {},
    ): RdfResource = reifies(RdfTriple(subject, predicate, obj), reifier, configure)
}
