package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL

/**
 * Container for multiple individual values using curly braces syntax.
 */
class MultipleIndividualValues(val values: List<Any>)

/**
 * Container for RDF list values using parentheses syntax.
 */
class RdfListValues(val values: List<Any>)

/**
 * Container for RDF Bag values.
 */
class RdfBagValues(val values: List<Any>)

/**
 * Container for RDF Seq values.
 */
class RdfSeqValues(val values: List<Any>)

/**
 * Container for RDF Alt values.
 */
class RdfAltValues(val values: List<Any>)

/**
 * Container for RDF-star embedded triples.
 */
class RdfStarTriple(val subject: Any, val predicate: Any, val obj: Any)

/**
 * Create multiple individual values using curly braces syntax: {value1, value2, value3}
 * Creates individual triples for each value.
 */
fun values(vararg values: Any): MultipleIndividualValues {
    return MultipleIndividualValues(values.toList())
}

/**
 * Create RDF list values using parentheses syntax: (value1, value2, value3)
 * Creates proper RDF List structure.
 */
fun list(vararg values: Any): RdfListValues {
    return RdfListValues(values.toList())
}


/**
 * Create RDF Bag values: bag(value1, value2, value3)
 * Creates rdf:Bag container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun bag(vararg values: Any): RdfBagValues {
    return RdfBagValues(values.toList())
}

/**
 * Create RDF Seq values: seq(value1, value2, value3)
 * Creates rdf:Seq container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun seq(vararg values: Any): RdfSeqValues {
    return RdfSeqValues(values.toList())
}

/**
 * Create RDF Alt values: alt(value1, value2, value3)
 * Creates rdf:Alt container with rdf:_1, rdf:_2, rdf:_3, etc.
 */
fun alt(vararg values: Any): RdfAltValues {
    return RdfAltValues(values.toList())
}

/**
 * Create an embedded triple for RDF-star using angle brackets syntax: <<subject predicate object>>
 * This creates a quoted triple that can be used as a subject or object in other triples.
 * 
 * Note: This function only creates the embedded triple structure. The actual RdfTriple
 * creation with TripleTerm will be handled by the DSL processing logic.
 */
fun embedded(subject: Any, predicate: Any, obj: Any): RdfStarTriple {
    return RdfStarTriple(subject, predicate, obj)
}

/**
 * Elegant DSL for creating RDF triples.
 * Supports multiple syntax styles for maximum developer productivity.
 */
class TripleDsl {
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
     * 
     * Example:
     * ```kotlin
     * repo.add {
     *     prefixes {
     *         "foaf" to "http://xmlns.com/foaf/0.1/"
     *         "dcat" to "http://www.w3.org/ns/dcat#"
     *     }
     *     
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
     * 
     * Example:
     * ```kotlin
     * val nameIri = qname("foaf:name")  // Resolves to http://xmlns.com/foaf/0.1/name
     * ```
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
}

/**
 * Helper class for minus operator syntax.
 */
data class SubjectPredicateChain(val subject: RdfResource, val predicate: Iri)









