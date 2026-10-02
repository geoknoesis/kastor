package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.reasoning.Rdf4jReasoner
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RDFS reasoners (Jena, RDF4J, memory; HermiT is OWL DL only) agree on what counts as an inference: triples that
 * hold without any data (the closure of the empty graph, including datatype axioms such as
 * `xsd:integer a rdfs:Class` that an engine only materialises once the data mentions the datatype) are not reported,
 * while everything that follows from the data is, also when it relates vocabulary terms only.
 */
class ReasonerAxiomParityTest {
    private val ex = "http://example.org/"
    private val vocabulary = listOf(
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "http://www.w3.org/2000/01/rdf-schema#",
        "http://www.w3.org/2002/07/owl#",
        "http://www.w3.org/2001/XMLSchema#",
    )
    private fun isVocabulary(term: Any) = term is Iri && vocabulary.any { term.value.startsWith(it) }
    private fun pureVocabulary(t: RdfTriple) = isVocabulary(t.subject) && isVocabulary(t.predicate) && isVocabulary(t.obj)

    private val data: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        ex:Person rdfs:subClassOf ex:Agent .
        ex:age rdfs:domain ex:Person ; rdfs:range xsd:integer .
        ex:name rdfs:subPropertyOf rdfs:label .
        ex:alice ex:age "42"^^xsd:integer ; ex:name "Alice" ; ex:height "1.7"^^xsd:decimal ; ex:born "1980-01-01"^^xsd:date .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    private val reasoners = mapOf(
        "jena" to { g: RdfGraph -> JenaReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
        "rdf4j" to { g: RdfGraph -> Rdf4jReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
        "memory" to { g: RdfGraph -> MemoryReasoner(ReasonerConfig.rdfs()).getInferredTriples(g) },
    )

    @Test
    fun `no reasoner reports pure vocabulary axioms triggered by typed literals`() {
        for ((name, infer) in reasoners) {
            val axioms = infer(data).filter(::pureVocabulary)
            assertTrue(axioms.isEmpty(), "$name reports vocabulary axioms as inferences: $axioms")
        }
    }

    @Test
    fun `the reasoners agree on inferences about the data's own terms`() {
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val label = Iri("http://www.w3.org/2000/01/rdf-schema#label")
        val expected = setOf(
            RdfTriple(Iri(ex + "alice"), type, Iri(ex + "Person")),
            RdfTriple(Iri(ex + "alice"), type, Iri(ex + "Agent")),
            RdfTriple(Iri(ex + "alice"), label, com.geoknoesis.kastor.rdf.Literal("Alice")),
        )
        // Only the RDFS rules every reasoner implements (subclass, subproperty, domain, range); the memory reasoner
        // does not add rdfs:Resource / rdf:Property typing, so those are compared separately above.
        val core = reasoners.mapValues { (_, infer) ->
            infer(data).filter { !pureVocabulary(it) && (it.obj !is Iri || !isVocabulary(it.obj)) && it.predicate != Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf") && it.predicate != Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf") }.toSet()
        }
        for ((name, inferred) in core) assertTrue(inferred.containsAll(expected), "$name misses ${expected - inferred}")
        assertEquals(core.getValue("memory"), core.getValue("jena"), "jena vs memory")
        assertEquals(core.getValue("memory"), core.getValue("rdf4j"), "rdf4j vs memory")
    }

    /** Schema the user asserts about vocabulary terms: an `owl:` property class hierarchy below `rdf:Property`. */
    private val vocabularySchema: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        owl:ObjectProperty rdfs:subClassOf rdf:Property .
        owl:FunctionalProperty rdfs:subClassOf owl:ObjectProperty .
        ex:knows a owl:FunctionalProperty ; rdfs:range xsd:string .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    @Test
    fun `inferences from user-asserted schema about vocabulary terms are reported by every reasoner`() {
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val rdfProperty = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")
        val objectProperty = Iri("http://www.w3.org/2002/07/owl#ObjectProperty")
        val functionalProperty = Iri("http://www.w3.org/2002/07/owl#FunctionalProperty")
        val expected = setOf(
            // Relates vocabulary terms only, but follows from the two asserted subclass statements.
            RdfTriple(functionalProperty, subClassOf, rdfProperty),
            RdfTriple(Iri(ex + "knows"), type, objectProperty),
            RdfTriple(Iri(ex + "knows"), type, rdfProperty),
        )
        // The memory reasoner implements the subclass, subproperty, domain and range rules only; Jena and RDF4J also
        // type every resource (rdfs:Resource, rdfs:Class) and add reflexive subclass / subproperty statements.
        val structuralTypes = setOf(Iri("http://www.w3.org/2000/01/rdf-schema#Resource"), Iri("http://www.w3.org/2000/01/rdf-schema#Class"))
        fun structural(t: RdfTriple) = (t.predicate == type && t.obj in structuralTypes) ||
            (t.predicate == subClassOf && (t.obj == t.subject || t.obj in structuralTypes)) ||
            (t.predicate == Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf") && t.obj == t.subject)
        val results = reasoners.mapValues { (_, infer) -> infer(vocabularySchema).toSet() }
        for ((name, inferred) in results) {
            assertTrue(inferred.containsAll(expected), "$name misses ${expected - inferred}")
            assertEquals(expected, inferred.filterNot(::structural).toSet(), "$name: inferences beyond the structural ones")
            // Datatype axioms are not inferences, although the schema mentions xsd:string.
            val datatypeAxioms = inferred.filter { it.subject == Iri("http://www.w3.org/2001/XMLSchema#string") }
            assertTrue(datatypeAxioms.isEmpty(), "$name reports datatype axioms: $datatypeAxioms")
        }
        // What Jena and RDF4J derive about the owl: classes from the asserted schema is kept as well.
        for (name in listOf("jena", "rdf4j")) {
            assertTrue(RdfTriple(objectProperty, type, Iri("http://www.w3.org/2000/01/rdf-schema#Class")) in results.getValue(name), name)
        }
    }

    /**
     * Data that mentions RDF and RDFS vocabulary individuals, classes, properties and container membership
     * properties, without asserting anything that relates vocabulary terms to each other.
     */
    private val vocabularyUse: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        ex:list rdf:first ex:a ; rdf:rest rdf:nil .
        ex:empty ex:items rdf:nil .
        ex:bag a rdf:Bag ; rdf:_1 ex:a ; rdf:_2 ex:b .
        ex:seq a rdf:Seq ; rdfs:member ex:a .
        ex:stmt a rdf:Statement ; rdf:subject ex:a ; rdf:predicate ex:p ; rdf:object ex:b .
        ex:a rdfs:seeAlso ex:b ; rdfs:isDefinedBy ex:onto ; rdf:value "v" ; rdfs:comment "c" ; rdfs:label "l" .
        ex:p a rdf:Property ; rdfs:domain ex:C ; rdfs:range rdfs:Literal .
        ex:C a rdfs:Class .
        ex:dt a rdfs:Datatype .
        ex:cmp a rdfs:ContainerMembershipProperty .
        ex:x ex:p "x"@en , "1"^^xsd:int , "<a/>"^^rdf:XMLLiteral .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    @Test
    fun `mentioning vocabulary individuals and membership properties does not turn their axioms into inferences`() {
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val rdfType = type
        val nil = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")
        for ((name, infer) in reasoners) {
            val inferred = infer(vocabularyUse)
            // Nothing in the data relates two vocabulary terms, so every pure vocabulary triple holds without it:
            // e.g. `rdf:nil a rdf:List` (axiomatic), `rdf:nil a rdfs:Resource` (rdfs4),
            // `rdf:_1 a rdfs:ContainerMembershipProperty` (axiomatic), `rdf:_1 rdfs:subPropertyOf rdfs:member` (rdfs12).
            val axioms = inferred.filter(::pureVocabulary)
            assertTrue(axioms.isEmpty(), "$name reports vocabulary axioms as inferences: $axioms")
            // What follows from the data about its own terms is still reported.
            assertTrue(RdfTriple(Iri(ex + "x"), type, Iri(ex + "C")) in inferred, "$name: domain inference")
            assertTrue(inferred.none { it.subject == nil }, "$name: ${inferred.filter { it.subject == nil }}")
        }
        // Jena's OWL rule sets type every node they meet (`rdf:nil a rdfs:Resource`): the RDFS axioms about
        // `rdf:nil` and the membership properties are not inferences there either.
        for (owl in listOf(com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_MICRO, com.geoknoesis.kastor.rdf.reasoning.ReasonerType.OWL_RL)) {
            val inferred = JenaReasoner(ReasonerConfig(reasonerType = owl)).getInferredTriples(vocabularyUse)
            val rdfsAxioms = inferred.filter { t ->
                val s = t.subject
                val o = t.obj
                s is Iri && o is Iri && com.geoknoesis.kastor.rdf.reasoning.RdfsAxioms.isEntailedByEmptyGraph(s.value, t.predicate.value, o.value)
            }
            assertTrue(rdfsAxioms.isEmpty(), "jena $owl reports RDFS axioms: $rdfsAxioms")
            val rdfsResource = Iri("http://www.w3.org/2000/01/rdf-schema#Resource")
            val list = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#List")
            assertTrue(RdfTriple(nil, rdfType, rdfsResource) !in inferred && RdfTriple(nil, rdfType, list) !in inferred, "jena $owl")
        }
    }

    /** Asserted schema that happens to derive a triple with the shape of an RDFS axiom. */
    private val axiomShaped: RdfGraph = JenaProvider().parseGraph(
        """
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <$ex> .
        rdf:Bag rdfs:subClassOf ex:Collection .
        ex:Collection rdfs:subClassOf rdfs:Resource .
        xsd:integer rdfs:subClassOf ex:Number .
        ex:Number rdfs:subClassOf rdfs:Literal .
        rdfs:label rdfs:subPropertyOf ex:name .
        ex:name rdfs:subPropertyOf rdfs:label .
        """.trimIndent().byteInputStream(),
        "TURTLE",
    )

    @Test
    fun `a rule subset has no axioms so a derived triple shaped like one is an inference`() {
        val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val subPropertyOf = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        val rdfs = "http://www.w3.org/2000/01/rdf-schema#"
        val label = Iri(rdfs + "label")
        val shaped = setOf(
            // rdfs11 over the two asserted statements; under the full rule set the same triples are axioms (rdfs8, rdfs13, rdfs6).
            RdfTriple(Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#Bag"), subClassOf, Iri(rdfs + "Resource")),
            RdfTriple(Iri("http://www.w3.org/2001/XMLSchema#integer"), subClassOf, Iri(rdfs + "Literal")),
            RdfTriple(label, subPropertyOf, label),
        )
        val subset = ReasonerConfig.rdfs().copy(
            enabledRules = setOf(
                com.geoknoesis.kastor.rdf.reasoning.ReasoningRule.RDFS_SUBCLASS,
                com.geoknoesis.kastor.rdf.reasoning.ReasoningRule.RDFS_SUBPROPERTY,
            ),
        )
        val jena = JenaReasoner(subset).getInferredTriples(axiomShaped).toSet()
        val memory = MemoryReasoner(subset).getInferredTriples(axiomShaped).toSet()
        assertTrue(jena.containsAll(shaped), "jena (rule subset) drops data-derived triples: ${shaped - jena}")
        assertEquals(memory, jena, "the Jena rule subset and the memory reasoner run the same rules")

        // The complete RDFS rule sets entail those triples from the empty graph: there they stay axioms.
        for (name in listOf("jena", "rdf4j")) {
            val full = reasoners.getValue(name)(axiomShaped).toSet()
            assertTrue(full.none { it in shaped }, "$name (full RDFS) reports axioms: ${full.filter { it in shaped }}")
            assertTrue(RdfTriple(Iri(ex + "name"), subPropertyOf, Iri(ex + "name")) in full, name)
        }
    }

    @Test
    fun `includeAxiomaticTriples brings the vocabulary axioms back`() {
        val include = ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))
        assertTrue(JenaReasoner(include).getInferredTriples(data).any(::pureVocabulary))
        assertTrue(Rdf4jReasoner(include).getInferredTriples(data).any(::pureVocabulary))
    }
}
