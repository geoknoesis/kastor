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
 * Elegant DSL for creating RDF triples.
 * Supports multiple syntax styles for maximum developer productivity.
 */
class TripleDsl {
    val triples = mutableListOf<RdfTriple>()
    
    // Bnode factory with counter for consistent naming
    private var bnodeCounter = 0
    private fun nextBnode(prefix: String = "b"): BlankNode {
        return bnode("${prefix}${++bnodeCounter}")
    }
    
    // === ULTRA-COMPACT SYNTAX ===
    
    /**
     * Ultra-compact bracket syntax: person["name"] = "Alice"
     */
    operator fun RdfResource.set(predicate: String, value: Any) {
        val obj = when (value) {
            is String -> Literal(value, XSD.string)
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value
            else -> Literal(value.toString(), XSD.string)
        }
        triples.add(RdfTriple(this, Iri(predicate), obj))
    }
    
    /**
     * Ultra-compact bracket syntax with IRI predicate: person[name] = "Alice"
     */
    operator fun RdfResource.set(predicate: Iri, value: Any) {
        val obj = when (value) {
            is String -> Literal(value, XSD.string)
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value
            else -> Literal(value.toString(), XSD.string)
        }
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
     * Natural language syntax: person has FOAF.name with "Alice"
     */
    infix fun SubjectAndPredicate.with(value: Any) {
        val obj = when (value) {
            is String -> Literal(value, XSD.string)
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value
            else -> Literal(value.toString(), XSD.string)
        }
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
        val obj = when (value) {
            is String -> Literal(value, XSD.string)
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value
            else -> Literal(value.toString(), XSD.string)
        }
        triples.add(RdfTriple(subject, predicate, obj))
    }
    
    /**
     * Minus operator with multiple values: person - FOAF.knows - [friend1, friend2, friend3]
     */
    infix operator fun <T> SubjectPredicateChain.minus(values: Array<T>) {
        values.forEach { value ->
            val obj = when (value) {
                is String -> Literal(value, XSD.string)
                is Int -> Literal(value.toString(), XSD.integer)
                is Double -> Literal(value.toString(), XSD.double)
                is Boolean -> Literal(value.toString(), XSD.boolean)
                is RdfTerm -> value
                else -> Literal(value.toString(), XSD.string)
            }
            triples.add(RdfTriple(subject, predicate, obj))
        }
    }
    
    /**
     * Minus operator with curly braces for individual triples: person - FOAF.knows - {friend1, friend2, friend3}
     * Creates multiple individual triples.
     */
    infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues) {
        values.values.forEach { value ->
            val obj = when (value) {
                is String -> Literal(value, XSD.string)
                is Int -> Literal(value.toString(), XSD.integer)
                is Double -> Literal(value.toString(), XSD.double)
                is Boolean -> Literal(value.toString(), XSD.boolean)
                is RdfTerm -> value
                else -> Literal(value.toString(), XSD.string)
            }
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
            val obj = when (value) {
                is String -> Literal(value, XSD.string)
                is Int -> Literal(value.toString(), XSD.integer)
                is Double -> Literal(value.toString(), XSD.double)
                is Boolean -> Literal(value.toString(), XSD.boolean)
                is RdfTerm -> value
                else -> Literal(value.toString(), XSD.string)
            }
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
            val obj = when (value) {
                is String -> Literal(value, XSD.string)
                is Int -> Literal(value.toString(), XSD.integer)
                is Double -> Literal(value.toString(), XSD.double)
                is Boolean -> Literal(value.toString(), XSD.boolean)
                is RdfTerm -> value
                else -> Literal(value.toString(), XSD.string)
            }
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
        private fun createRdfList(values: List<Any>): RdfTerm {
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
                val memberProperty = IRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
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
                val memberProperty = IRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
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
                val memberProperty = IRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#_${index + 1}")
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
     * Create a triple with string predicate.
     */
    fun triple(subject: RdfResource, predicate: String, obj: RdfTerm) {
        triples.add(RdfTriple(subject, Iri(predicate), obj))
    }
    
    /**
     * Add multiple triples to the DSL.
     */
    fun addTriples(newTriples: Collection<RdfTriple>) {
        triples.addAll(newTriples)
    }
}

/**
 * Helper class for natural language syntax.
 */
data class SubjectAndPredicate(val subject: RdfResource, val predicate: Iri)

/**
 * Helper class for minus operator syntax.
 */
data class SubjectPredicateChain(val subject: RdfResource, val predicate: Iri)
