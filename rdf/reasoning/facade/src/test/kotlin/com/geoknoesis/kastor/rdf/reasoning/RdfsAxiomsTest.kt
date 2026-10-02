package com.geoknoesis.kastor.rdf.reasoning

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [RdfsAxioms] covers exactly the data-independent RDFS trivia, never an inference that depends on asserted schema. */
class RdfsAxiomsTest {
    private val rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val rdfs = "http://www.w3.org/2000/01/rdf-schema#"
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private val owl = "http://www.w3.org/2002/07/owl#"
    private val ex = "http://example.org/"

    private fun axiom(s: String, p: String, o: String, owlRules: Boolean = false) = RdfsAxioms.isEntailedByEmptyGraph(s, p, o, owlRules)

    @Test
    fun `datatype axioms hold for recognised datatypes only`() {
        for (datatype in listOf(xsd + "integer", xsd + "string", xsd + "dateTime", rdf + "langString")) {
            assertTrue(axiom(datatype, rdf + "type", rdfs + "Datatype"), datatype)
            assertTrue(axiom(datatype, rdf + "type", rdfs + "Class"), datatype)
            assertTrue(axiom(datatype, rdf + "type", rdfs + "Resource"), datatype)
            assertTrue(axiom(datatype, rdfs + "subClassOf", rdfs + "Literal"), datatype)
            assertTrue(axiom(datatype, rdfs + "subClassOf", rdfs + "Resource"), datatype)
            assertTrue(axiom(datatype, rdfs + "subClassOf", datatype), datatype)
        }
        // A user datatype, or a name that merely lives in the XSD namespace, is not recognised.
        for (other in listOf(ex + "celsius", xsd + "noSuchType")) {
            assertFalse(axiom(other, rdf + "type", rdfs + "Datatype"), other)
            assertFalse(axiom(other, rdf + "type", rdfs + "Class"), other)
            assertFalse(axiom(other, rdfs + "subClassOf", other), other)
        }
        // A datatype related to anything else is an inference from the data.
        assertFalse(axiom(xsd + "integer", rdfs + "subClassOf", xsd + "decimal"))
        assertFalse(axiom(xsd + "integer", rdfs + "subClassOf", ex + "Number"))
    }

    @Test
    fun `reflexive and typing axioms hold for the RDF and RDFS vocabulary`() {
        assertTrue(axiom(rdfs + "label", rdfs + "subPropertyOf", rdfs + "label"))
        assertTrue(axiom(rdfs + "label", rdf + "type", rdf + "Property"))
        assertTrue(axiom(rdf + "_7", rdfs + "subPropertyOf", rdf + "_7"))
        assertTrue(axiom(rdf + "Property", rdfs + "subClassOf", rdf + "Property"))
        assertTrue(axiom(rdfs + "Class", rdf + "type", rdfs + "Class"))
        // A property is not a class: these do not hold without asserted schema.
        assertFalse(axiom(rdfs + "label", rdfs + "subClassOf", rdfs + "label"))
        assertFalse(axiom(rdfs + "label", rdf + "type", rdfs + "Class"))
        assertFalse(axiom(rdf + "Property", rdfs + "subPropertyOf", rdf + "Property"))
        assertFalse(axiom(rdfs + "label", rdfs + "subPropertyOf", rdfs + "comment"))
    }

    @Test
    fun `statements about owl terms and user terms are never axioms`() {
        assertFalse(axiom(owl + "FunctionalProperty", rdfs + "subClassOf", rdf + "Property"))
        assertFalse(axiom(owl + "ObjectProperty", rdf + "type", rdfs + "Class"))
        assertFalse(axiom(owl + "ObjectProperty", rdfs + "subClassOf", owl + "ObjectProperty"))
        assertFalse(axiom(owl + "imports", rdfs + "subPropertyOf", owl + "imports"))
        assertFalse(axiom(ex + "Person", rdf + "type", rdfs + "Class"))
        assertFalse(axiom(ex + "Person", rdfs + "subClassOf", ex + "Person"))
        assertFalse(axiom(rdfs + "seeAlso", rdfs + "subPropertyOf", ex + "link"))
    }

    @Test
    fun `owl trivia are axioms only for owl rule sets`() {
        assertFalse(axiom(xsd + "integer", owl + "equivalentClass", xsd + "integer"))
        assertTrue(axiom(xsd + "integer", owl + "equivalentClass", xsd + "integer", owlRules = true))
        assertTrue(axiom(xsd + "integer", rdf + "type", owl + "Class", owlRules = true))
        assertTrue(axiom(rdfs + "label", owl + "equivalentProperty", rdfs + "label", owlRules = true))
        assertFalse(axiom(rdf + "first", rdf + "type", owl + "ObjectProperty", owlRules = true))
    }

    @Test
    fun `container membership properties and rdf nil have their axioms`() {
        for (n in listOf("_1", "_2", "_117")) {
            assertTrue(axiom(rdf + n, rdf + "type", rdfs + "ContainerMembershipProperty"), n)
            assertTrue(axiom(rdf + n, rdfs + "subPropertyOf", rdfs + "member"), n)
            assertTrue(axiom(rdf + n, rdfs + "domain", rdfs + "Resource"), n)
            assertTrue(axiom(rdf + n, rdfs + "range", rdfs + "Resource"), n)
            assertTrue(axiom(rdf + n, rdf + "type", rdf + "Property"), n)
            assertTrue(axiom(rdf + n, rdf + "type", rdfs + "Resource"), n)
        }
        assertTrue(axiom(rdf + "nil", rdf + "type", rdf + "List"))
        assertTrue(axiom(rdf + "nil", rdf + "type", rdfs + "Resource"))

        // Not membership properties: `rdf:_0`, `rdf:_01`, a user property, another vocabulary property.
        for (other in listOf(rdf + "_0", rdf + "_01", rdf + "_x", ex + "_1", rdfs + "label")) {
            assertFalse(axiom(other, rdf + "type", rdfs + "ContainerMembershipProperty"), other)
            assertFalse(axiom(other, rdfs + "subPropertyOf", rdfs + "member"), other)
            assertFalse(axiom(other, rdfs + "domain", rdfs + "Resource"), other)
        }
        // Anything else said about them follows from the data.
        assertFalse(axiom(rdf + "_1", rdfs + "subPropertyOf", ex + "item"))
        assertFalse(axiom(rdf + "_1", rdfs + "domain", rdf + "Bag"))
        assertFalse(axiom(rdf + "_1", rdfs + "range", rdfs + "Literal"))
        assertFalse(axiom(rdf + "nil", rdf + "type", rdf + "Bag"))
        assertFalse(axiom(rdf + "nil", rdf + "type", rdfs + "Class"))
        assertFalse(axiom(ex + "emptyList", rdf + "type", rdf + "List"))
        assertFalse(axiom(rdfs + "member", rdfs + "subPropertyOf", rdf + "_1"))
    }
}
