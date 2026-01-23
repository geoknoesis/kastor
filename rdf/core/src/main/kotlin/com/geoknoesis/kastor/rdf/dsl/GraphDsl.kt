package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL

/**
 * DSL for creating standalone RDF graphs.
 * Provides all the same syntax options as TripleDsl but builds a complete RdfGraph.
 */
class GraphDsl {
    val triples = mutableListOf<RdfTriple>()
    
    // Prefix mappings for QName resolution
    private val prefixMappings = mutableMapOf<String, String>().apply {
        // Initialize with built-in prefixes for common vocabularies
        putBuiltInPrefixes()
    }
    
    /**
     * Initialize built-in prefixes for common vocabularies.
     */
    private fun MutableMap<String, String>.putBuiltInPrefixes() {
        put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
        put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
        put("owl", "http://www.w3.org/2002/07/owl#")
        put("sh", "http://www.w3.org/ns/shacl#")
        put("xsd", "http://www.w3.org/2001/XMLSchema#")
    }
    
    // Bnode factory with counter for consistent naming
    private var bnodeCounter = 0
    private fun nextBnode(prefix: String = "b"): BlankNode {
        return bnode("${prefix}${++bnodeCounter}")
    }
    
    // === PREFIX MAPPING CONFIGURATION ===
    
    /**
     * Configure prefix mappings for QName resolution.
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
     * Resolve a QName or IRI string to an Iri object.
     */
    private fun resolveIri(iriOrQName: String): Iri {
        val resolved = QNameResolver.resolve(iriOrQName, prefixMappings)
        return Iri(resolved)
    }
    
    
    /**
     * Create an IRI from a QName or full IRI string.
     */
    fun qname(iriOrQName: String): Iri {
        return resolveIri(iriOrQName)
    }
    
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
        triples.add(RdfTriple(subject, predicate, value))
    }

    /**
     * Bracket assignment syntax: person[FOAF.name] = "Alice"
     */
    operator fun RdfResource.set(predicate: Iri, value: RdfTerm) {
        triples.add(RdfTriple(this, predicate, value))
    }

    operator fun RdfResource.set(predicate: Iri, value: String) {
        triples.add(RdfTriple(this, predicate, string(value)))
    }

    operator fun RdfResource.set(predicate: Iri, value: Int) {
        triples.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Long) {
        triples.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Double) {
        triples.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Float) {
        triples.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: Boolean) {
        triples.add(RdfTriple(this, predicate, value.toLiteral()))
    }

    operator fun RdfResource.set(predicate: Iri, value: RdfResource) {
        triples.add(RdfTriple(this, predicate, value))
    }

    /**
     * Natural language alias for rdf:type: person `is` FOAF.Person
     */
    infix fun RdfResource.`is`(type: RdfResource) {
        triples.add(RdfTriple(this, RDF.type, type))
    }

    /**
     * Natural language has/with syntax: person has FOAF.name with "Alice"
     */
    infix fun RdfResource.has(predicate: Iri): SubjectPredicateChain {
        return SubjectPredicateChain(this, predicate)
    }

    infix fun SubjectPredicateChain.with(value: RdfTerm) {
        triples.add(RdfTriple(subject, predicate, value))
    }

    infix fun SubjectPredicateChain.with(value: String) {
        triples.add(RdfTriple(subject, predicate, string(value)))
    }

    infix fun SubjectPredicateChain.with(value: Int) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Long) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Double) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Float) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: Boolean) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix fun SubjectPredicateChain.with(value: RdfResource) {
        triples.add(RdfTriple(subject, predicate, value))
    }

    /**
     * Convenience overloads for common literal types.
     */
    infix operator fun SubjectPredicateChain.minus(value: String) {
        triples.add(RdfTriple(subject, predicate, string(value)))
    }

    infix operator fun SubjectPredicateChain.minus(value: Int) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Long) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Double) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Float) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    infix operator fun SubjectPredicateChain.minus(value: Boolean) {
        triples.add(RdfTriple(subject, predicate, value.toLiteral()))
    }

    
    /**
     * Minus operator with RDF list: person - FOAF.knows - rdfList(friend1, friend2, friend3)
     * Creates proper RDF List structure.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfListValues) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.values)))
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<out RdfTerm>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value)) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<String>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, string(value))) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Int>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Long>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Double>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Float>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: Array<Boolean>) {
        values.forEach { value -> triples.add(RdfTriple(subject, predicate, value.toLiteral())) }
    }

    infix operator fun SubjectPredicateChain.minus(values: List<out RdfTerm>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values)))
    }

    @JvmName("minusStringList")
    infix operator fun SubjectPredicateChain.minus(values: List<String>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { string(it) })))
    }

    @JvmName("minusIntList")
    infix operator fun SubjectPredicateChain.minus(values: List<Int>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusLongList")
    infix operator fun SubjectPredicateChain.minus(values: List<Long>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusDoubleList")
    infix operator fun SubjectPredicateChain.minus(values: List<Double>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusFloatList")
    infix operator fun SubjectPredicateChain.minus(values: List<Float>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    @JvmName("minusBooleanList")
    infix operator fun SubjectPredicateChain.minus(values: List<Boolean>) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.map { it.toLiteral() })))
    }

    infix operator fun SubjectPredicateChain.minus(values: Pair<RdfTerm, RdfTerm>) {
        triples.add(RdfTriple(subject, predicate, values.first))
        triples.add(RdfTriple(subject, predicate, values.second))
    }

    @JvmName("minusStringPair")
    infix operator fun SubjectPredicateChain.minus(values: Pair<String, String>) {
        triples.add(RdfTriple(subject, predicate, string(values.first)))
        triples.add(RdfTriple(subject, predicate, string(values.second)))
    }

    infix operator fun SubjectPredicateChain.minus(values: Triple<RdfTerm, RdfTerm, RdfTerm>) {
        triples.add(RdfTriple(subject, predicate, values.first))
        triples.add(RdfTriple(subject, predicate, values.second))
        triples.add(RdfTriple(subject, predicate, values.third))
    }

    @JvmName("minusStringTriple")
    infix operator fun SubjectPredicateChain.minus(values: Triple<String, String, String>) {
        triples.add(RdfTriple(subject, predicate, string(values.first)))
        triples.add(RdfTriple(subject, predicate, string(values.second)))
        triples.add(RdfTriple(subject, predicate, string(values.third)))
    }
    
    /**
     * Minus operator with multiple individual values: person - FOAF.knows - multiple(friend1, friend2)
     * Creates individual triples for each value.
     */
    infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues) {
        values.values.forEach { value ->
            triples.add(RdfTriple(subject, predicate, value))
        }
    }
    
    /**
     * Minus operator with RDF Bag: person - DCTERMS.subject - bag("Tech", "AI", "RDF")
     * Creates rdf:Bag container with rdf:_1, rdf:_2, rdf:_3, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfBagValues) {
        val rdfBag = createRdfBag(values.values)
        triples.add(RdfTriple(subject, predicate, rdfBag))
    }
    
    /**
     * Minus operator with RDF Seq: person - FOAF.knows - seq(friend1, friend2, friend3)
     * Creates rdf:Seq container with rdf:_1, rdf:_2, rdf:_3, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfSeqValues) {
        val rdfSeq = createRdfSeq(values.values)
        triples.add(RdfTriple(subject, predicate, rdfSeq))
    }
    
    /**
     * Minus operator with RDF Alt: person - FOAF.mbox - alt("email1@example.com", "email2@example.com")
     * Creates rdf:Alt container with rdf:_1, rdf:_2, etc.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfAltValues) {
        val rdfAlt = createRdfAlt(values.values)
        triples.add(RdfTriple(subject, predicate, rdfAlt))
    }
    
        /**
         * Creates an RDF List from Kotlin values.
         */
        private fun createRdfList(values: List<RdfTerm>): RdfTerm {
            if (values.isEmpty()) return RDF.nil
            
            val listHead = nextBnode()
            var currentNode = listHead
            
            values.forEachIndexed { index, element ->
                triples.add(RdfTriple(currentNode, RDF.first, element))
                if (index < values.size - 1) {
                    val nextNode = nextBnode()
                    triples.add(RdfTriple(currentNode, RDF.rest, nextNode))
                    currentNode = nextNode
                } else {
                    triples.add(RdfTriple(currentNode, RDF.rest, RDF.nil))
                }
            }
            
            return listHead
        }
        
        /**
         * Creates an RDF Bag from values.
         */
        private fun createRdfBag(values: List<RdfTerm>): RdfTerm {
            val bagNode = nextBnode()
            triples.add(RdfTriple(bagNode, RDF.type, RDF.Bag))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(bagNode, memberProperty, value))
            }
            return bagNode
        }
        
        /**
         * Creates an RDF Seq from values.
         */
        private fun createRdfSeq(values: List<RdfTerm>): RdfTerm {
            val seqNode = nextBnode()
            triples.add(RdfTriple(seqNode, RDF.type, RDF.Seq))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(seqNode, memberProperty, value))
            }
            return seqNode
        }
        
        /**
         * Creates an RDF Alt from values.
         */
        private fun createRdfAlt(values: List<RdfTerm>): RdfTerm {
            val altNode = nextBnode()
            triples.add(RdfTriple(altNode, RDF.type, RDF.Alt))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(altNode, memberProperty, value))
            }
            return altNode
        }
    
    
    // === EXPLICIT TRIPLE CREATION ===
    
    /**
     * Create a triple explicitly.
     */
    fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm) {
        triples.add(RdfTriple(subject, predicate, obj))
    }
    
    /**
     * Add multiple triples to the DSL.
     */
    fun addTriples(newTriples: Collection<RdfTriple>) {
        triples.addAll(newTriples)
    }
    
    // === GRAPH BUILDING ===
    
    /**
     * Build the final RdfGraph from the collected triples.
     */
    fun build(): MutableRdfGraph {
        return MemoryGraph(triples.toList())
    }
}









