package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
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
     * Converts a value to an RDF term.
     * - Strings are always string literals.
     * - Use [Iri] or [qname] for IRIs.
     */
    private fun Any.toRdfTerm(): RdfTerm = when (this) {
        is String -> Literal(this, XSD.string)
        is Int -> Literal(toString(), XSD.integer)
        is Double -> Literal(toString(), XSD.double)
        is Boolean -> Literal(toString(), XSD.boolean)
        is RdfTerm -> this
        is RdfStarTriple -> {
            // Create an embedded triple for RDF-star
            // Note: We use a local helper that doesn't handle RdfStarTriple to avoid infinite recursion
            fun toTerm(v: Any): RdfTerm = when (v) {
                is String -> Literal(v, XSD.string)
                is Int -> Literal(v.toString(), XSD.integer)
                is Double -> Literal(v.toString(), XSD.double)
                is Boolean -> Literal(v.toString(), XSD.boolean)
                is RdfTerm -> v
                else -> Literal(v.toString(), XSD.string)
            }
            val embeddedSubject = toTerm(this.subject)
            val embeddedPredicate = toTerm(this.predicate)
            val embeddedObject = toTerm(this.obj)
            
            // Ensure subject and predicate are resources
            val subjectResource = when (embeddedSubject) {
                is RdfResource -> embeddedSubject
                else -> throw IllegalArgumentException("RDF-star embedded triple subject must be a resource, got: ${embeddedSubject::class.simpleName}")
            }
            val predicateIri = when (embeddedPredicate) {
                is Iri -> embeddedPredicate
                else -> throw IllegalArgumentException("RDF-star embedded triple predicate must be an IRI, got: ${embeddedPredicate::class.simpleName}")
            }
            
            TripleTerm(RdfTriple(subjectResource, predicateIri, embeddedObject))
        }
        else -> Literal(toString(), XSD.string)
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
    infix operator fun SubjectPredicateChain.minus(value: Any) {
        triples.add(RdfTriple(subject, predicate, value.toRdfTerm()))
    }
    
    /**
     * Minus operator with RDF list: person - FOAF.knows - rdfList(friend1, friend2, friend3)
     * Creates proper RDF List structure.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfListValues) {
        triples.add(RdfTriple(subject, predicate, createRdfList(values.values)))
    }
    
    /**
     * Minus operator with multiple individual values: person - FOAF.knows - multiple(friend1, friend2)
     * Creates individual triples for each value.
     */
    infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues) {
        values.values.forEach { value ->
            triples.add(RdfTriple(subject, predicate, value.toRdfTerm()))
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
        private fun createRdfList(values: List<Any>): RdfTerm {
            if (values.isEmpty()) return RDF.nil
            
            val listNodes = values.map { it.toRdfTerm() }
            val listHead = nextBnode()
            var currentNode = listHead
            
            listNodes.forEachIndexed { index, element ->
                triples.add(RdfTriple(currentNode, RDF.first, element))
                if (index < listNodes.size - 1) {
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
        private fun createRdfBag(values: List<Any>): RdfTerm {
            val bagNode = nextBnode()
            triples.add(RdfTriple(bagNode, RDF.type, RDF.Bag))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(bagNode, memberProperty, value.toRdfTerm()))
            }
            return bagNode
        }
        
        /**
         * Creates an RDF Seq from values.
         */
        private fun createRdfSeq(values: List<Any>): RdfTerm {
            val seqNode = nextBnode()
            triples.add(RdfTriple(seqNode, RDF.type, RDF.Seq))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(seqNode, memberProperty, value.toRdfTerm()))
            }
            return seqNode
        }
        
        /**
         * Creates an RDF Alt from values.
         */
        private fun createRdfAlt(values: List<Any>): RdfTerm {
            val altNode = nextBnode()
            triples.add(RdfTriple(altNode, RDF.type, RDF.Alt))
            values.forEachIndexed { index, value ->
                val memberProperty = Iri.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(altNode, memberProperty, value.toRdfTerm()))
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









