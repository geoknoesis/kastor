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
     * Smart object creation with explicit semantics.
     * - Strings are always string literals.
     * - Use [Iri] or [qname] for IRIs.
     */
    internal fun createSmartObject(value: Any, predicate: Iri): RdfTerm {
        return when (value) {
            is String -> {
                Literal(value, XSD.string)
            }
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value  // Already resolved (qname(), Iri(), literal(), etc.)
            is RdfStarTriple -> {
                // Create an embedded triple for RDF-star
                val embeddedSubject = createSmartObject(value.subject, predicate)
                val embeddedPredicate = createSmartObject(value.predicate, predicate)
                val embeddedObject = createSmartObject(value.obj, predicate)
                
                // Ensure subject and predicate are resources
                val subjectResource = when (embeddedSubject) {
                    is RdfResource -> embeddedSubject
                    else -> throw IllegalArgumentException("RDF-star embedded triple subject must be a resource, got: ${embeddedSubject::class.simpleName}")
                }
                val predicateIri = when (embeddedPredicate) {
                    is Iri -> embeddedPredicate
                    else -> throw IllegalArgumentException("RDF-star embedded triple predicate must be an IRI, got: ${embeddedPredicate::class.simpleName}")
                }
                
                val embeddedTriple = RdfTriple(subjectResource, predicateIri, embeddedObject)
                TripleTerm(embeddedTriple)
            }
            else -> Literal(value.toString(), XSD.string)
        }
    }
    
    /**
     * Create an IRI from a QName or full IRI string.
     */
    fun qname(iriOrQName: String): Iri {
        return resolveIri(iriOrQName)
    }
    
    // === ULTRA-COMPACT SYNTAX ===
    
    /**
     * Ultra-compact bracket syntax: person[FOAF.name] = "Alice"
     * Use qname("foaf:name") for QNames.
     */
    operator fun RdfResource.set(predicate: Iri, value: Any) {
        val obj = createSmartObject(value, predicate)
        triples.add(RdfTriple(this, predicate, obj))
    }
    
    // === NATURAL LANGUAGE SYNTAX ===
    
    /**
     * Natural language syntax: person has FOAF.name with "Alice"
     */
    infix fun RdfResource.has(predicate: Iri): SubjectAndPredicate {
        return SubjectAndPredicate(this, predicate)
    }
    
    /**
     * Natural language syntax: person `is` FOAF.Person
     * Directly creates a type triple without requiring 'with'
     */
    infix fun RdfResource.`is`(typeIri: Iri) {
        triples.add(RdfTriple(this, RDF.type, typeIri))
    }
    
    /**
     * Natural language syntax: person has FOAF.name with "Alice"
     */
    infix fun SubjectAndPredicate.with(value: Any) {
        val obj = createSmartObject(value, predicate)
        triples.add(RdfTriple(subject, predicate, obj))
    }
    
    // === MINUS OPERATOR SYNTAX ===
    
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
        val obj = createSmartObject(value, predicate)
        triples.add(RdfTriple(subject, predicate, obj))
    }
    
    /**
     * Minus operator with multiple values: person - FOAF.knows - [friend1, friend2, friend3]
     */
    infix operator fun <T> SubjectPredicateChain.minus(values: Array<T>) {
        values.forEach { value ->
            val obj = createSmartObject(value as Any, predicate)
            triples.add(RdfTriple(subject, predicate, obj))
        }
    }
    
    
    /**
     * Minus operator with curly braces for individual triples: person - FOAF.knows - {friend1, friend2, friend3}
     * Creates multiple individual triples.
     */
    infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues) {
        values.values.forEach { value ->
            val obj = createSmartObject(value, predicate)
            triples.add(RdfTriple(subject, predicate, obj))
        }
    }
    
    /**
     * Minus operator with parentheses for RDF lists: person - FOAF.knows - (friend1, friend2, friend3)
     * Creates proper RDF List structure.
     */
    infix operator fun SubjectPredicateChain.minus(values: RdfListValues) {
        val rdfList = createRdfList(values.values)
        triples.add(RdfTriple(subject, predicate, rdfList))
    }
    
    /**
     * Convenience: Minus operator with Pair for 2 values: person - FOAF.knows - (friend1, friend2)
     * Creates individual triples for each value.
     */
    infix operator fun SubjectPredicateChain.minus(pair: Pair<*, *>) {
        val values = listOf(pair.first, pair.second)
        values.forEach { value ->
            val obj = createSmartObject(value as Any, predicate)
            triples.add(RdfTriple(subject, predicate, obj))
        }
    }
    
    /**
     * Convenience: Minus operator with Triple for 3 values: person - FOAF.mbox - (email1, email2, email3)
     * Creates individual triples for each value.
     */
    infix operator fun SubjectPredicateChain.minus(triple: Triple<*, *, *>) {
        val values = listOf(triple.first, triple.second, triple.third)
        values.forEach { value ->
            val obj = createSmartObject(value as Any, predicate)
            triples.add(RdfTriple(subject, predicate, obj))
        }
    }
    
    /**
     * Minus operator with multiple values: person - FOAF.knows - listOf(friend1, friend2, friend3)
     * Creates an RDF List instead of multiple individual triples.
     */
    infix operator fun SubjectPredicateChain.minus(values: List<Any>) {
        val rdfList = createRdfList(values)
        triples.add(RdfTriple(subject, predicate, rdfList))
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
         * Helper function to create RDF Lists from Kotlin lists.
         */
        internal fun createRdfList(values: List<Any>): RdfTerm {
            if (values.isEmpty()) {
                return RDF.nil
            }
            
            val listNodes = values.map { value ->
                when (value) {
                    is String -> Literal(value, XSD.string)
                    is Int -> Literal(value.toString(), XSD.integer)
                    is Double -> Literal(value.toString(), XSD.double)
                    is Boolean -> Literal(value.toString(), XSD.boolean)
                    is RdfTerm -> value
                    else -> Literal(value.toString(), XSD.string)
                }
            }
            
            // Create RDF List structure using blank nodes with sequential names
            val listHead = nextBnode()
            var currentNode = listHead
            
            // Create triples for each element in the list
            listNodes.forEachIndexed { index, element ->
                triples.add(RdfTriple(currentNode, RDF.first, element))
                
                if (index < listNodes.size - 1) {
                    val nextNode = nextBnode()
                    triples.add(RdfTriple(currentNode, RDF.rest, nextNode))
                    currentNode = nextNode
                } else {
                    // Last element points to nil
                    triples.add(RdfTriple(currentNode, RDF.rest, RDF.nil))
                }
            }
            
            return listHead
        }
        
        /**
         * Helper function to create RDF Bag from values.
         */
        private fun createRdfBag(values: List<Any>): RdfTerm {
            val bagNode = nextBnode()
            
            // Add type declaration
            triples.add(RdfTriple(bagNode, RDF.type, RDF.Bag))
            
            // Add each value with rdf:_1, rdf:_2, etc.
            values.forEachIndexed { index, value ->
                val obj = when (value) {
                    is String -> Literal(value, XSD.string)
                    is Int -> Literal(value.toString(), XSD.integer)
                    is Double -> Literal(value.toString(), XSD.double)
                    is Boolean -> Literal(value.toString(), XSD.boolean)
                    is RdfTerm -> value
                    else -> Literal(value.toString(), XSD.string)
                }
                val memberProperty = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(bagNode, memberProperty, obj))
            }
            
            return bagNode
        }
        
        /**
         * Helper function to create RDF Seq from values.
         */
        private fun createRdfSeq(values: List<Any>): RdfTerm {
            val seqNode = nextBnode()
            
            // Add type declaration
            triples.add(RdfTriple(seqNode, RDF.type, RDF.Seq))
            
            // Add each value with rdf:_1, rdf:_2, etc.
            values.forEachIndexed { index, value ->
                val obj = when (value) {
                    is String -> Literal(value, XSD.string)
                    is Int -> Literal(value.toString(), XSD.integer)
                    is Double -> Literal(value.toString(), XSD.double)
                    is Boolean -> Literal(value.toString(), XSD.boolean)
                    is RdfTerm -> value
                    else -> Literal(value.toString(), XSD.string)
                }
                val memberProperty = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(seqNode, memberProperty, obj))
            }
            
            return seqNode
        }
        
        /**
         * Helper function to create RDF Alt from values.
         */
        private fun createRdfAlt(values: List<Any>): RdfTerm {
            val altNode = nextBnode()
            
            // Add type declaration
            triples.add(RdfTriple(altNode, RDF.type, RDF.Alt))
            
            // Add each value with rdf:_1, rdf:_2, etc.
            values.forEachIndexed { index, value ->
                val obj = when (value) {
                    is String -> Literal(value, XSD.string)
                    is Int -> Literal(value.toString(), XSD.integer)
                    is Double -> Literal(value.toString(), XSD.double)
                    is Boolean -> Literal(value.toString(), XSD.boolean)
                    is RdfTerm -> value
                    else -> Literal(value.toString(), XSD.string)
                }
                val memberProperty = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
                triples.add(RdfTriple(altNode, memberProperty, obj))
            }
            
            return altNode
        }
    
    
    // === TURTLE-STYLE COMPACT SYNTAX ===
    
    /**
     * Turtle-style compact syntax for multiple properties on same subject.
     * Usage: subject.properties { name("Alice"); age(30); knows(friend) }
     */
    fun RdfResource.properties(configure: SubjectPropertyDsl.() -> Unit) {
        val dsl = SubjectPropertyDsl(this).apply(configure)
        triples.addAll(dsl.triples)
    }
    
    /**
     * Turtle-style compact syntax with semicolon separators.
     * Usage: subject.ttl { name("Alice"); age(30); knows(friend) }
     */
    fun RdfResource.ttl(configure: SubjectPropertyDsl.() -> Unit) {
        properties(configure)
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









