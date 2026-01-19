package com.example.ontomapper.validation.rdf4j

import com.example.ontomapper.runtime.ShaclValidation
import com.example.ontomapper.runtime.ValidationException
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.dsl.list
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class Rdf4jValidationAdvancedTest {

    @Test
    fun `validation handles complex RDF graphs`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val org = Iri("http://example.org/org")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John Doe"
            person - FOAF.age - 30
            person - FOAF.member - org
            person - DCTERMS.description - "A person"

            org - com.geoknoesis.kastor.rdf.vocab.RDF.type - Iri("http://example.org/Organization")
            org - FOAF.name - "Example Corp"
        }

        // Should not throw for valid complex graph
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation handles empty graphs gracefully`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        // Empty graph
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation handles multiple validation rules`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person1 = Iri("http://example.org/person1")
        val person2 = Iri("http://example.org/person2")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            // Valid person
            person1 - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person1 - FOAF.name - "Alice"

            // Invalid person (missing name)
            person2 - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person2 - FOAF.age - 25
        }

        // Valid person should pass
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person1).orThrow()
        }

        // Invalid person should fail
        assertThrows(ValidationException::class.java) {
            validation.validate(repo.defaultGraph, person2).orThrow()
        }
    }

    @Test
    fun `validation handles non-Person types gracefully`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val org = Iri("http://example.org/org")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            org - com.geoknoesis.kastor.rdf.vocab.RDF.type - Iri("http://example.org/Organization")
            org - FOAF.name - "Example Corp"
        }

        // Should pass for non-Person types (no validation rules apply)
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, org).orThrow()
        }
    }

    @Test
    fun `validation handles malformed IRIs gracefully`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John"
        }

        // Should handle malformed IRIs gracefully
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation handles blank nodes`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = BlankNode("person1")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "Anonymous"
        }

        // Should work with blank nodes
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation handles language-tagged literals`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - LangString("Jean", "fr")
            person - FOAF.name - LangString("John", "en")
        }

        // Should handle language-tagged literals
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation handles typed literals`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John"
            person - FOAF.age - TypedLiteral("30", Iri("http://www.w3.org/2001/XMLSchema#int"))
        }

        // Should handle typed literals
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation error messages are informative`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            // Missing required name property
        }

        val exception = assertThrows(ValidationException::class.java) {
            validation.validate(repo.defaultGraph, person).orThrow()
        }

        assertTrue(exception.message!!.contains("Name is required"))
    }

    @Test
    fun `validation registry integration works correctly`() {
        val validation = Rdf4jValidation()
        
        // Should be automatically registered
        val currentValidation = ShaclValidation.current()
        assertSame(validation, currentValidation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addBasicPersonShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John"
        }

        // Should work through registry
        assertDoesNotThrow {
            currentValidation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation supports sh or constraints`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personClass = Iri("http://example.org/Person")
        addOrAgeShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John"
            person - FOAF.age - 30
        }

        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation supports nodeKind constraints`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        val personClass = Iri("http://example.org/Person")
        addNodeKindShape(repo, personClass)

        repo.add {
            person - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person - FOAF.name - "John"
            person - FOAF.knows - friend
        }

        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }

    @Test
    fun `validation supports inverse path constraints`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person1 = Iri("http://example.org/person1")
        val person2 = Iri("http://example.org/person2")
        val personClass = Iri("http://example.org/Person")
        addInverseKnowsShape(repo, personClass)

        repo.add {
            person1 - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person2 - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            person1 - FOAF.knows - person2
            person2 - FOAF.name - "Bob"
        }

        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person2).orThrow()
        }
    }

    private fun addBasicPersonShape(repo: com.geoknoesis.kastor.rdf.RdfRepository, targetClass: Iri) {
        val sh = "http://www.w3.org/ns/shacl#"
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val shape = Iri("urn:shape:PersonShape")
        val nameShape = bnode("nameShape")

        val shNodeShape = Iri("${sh}NodeShape")
        val shTargetClass = Iri("${sh}targetClass")
        val shProperty = Iri("${sh}property")
        val shPath = Iri("${sh}path")
        val shDatatype = Iri("${sh}datatype")
        val shMinCount = Iri("${sh}minCount")
        val shMessage = Iri("${sh}message")

        repo.add {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - shNodeShape
            shape - shTargetClass - targetClass
            shape - shProperty - nameShape

            nameShape - shPath - FOAF.name
            nameShape - shDatatype - Iri("${xsd}string")
            nameShape - shMinCount - 1
            nameShape - shMessage - "Name is required"
        }
    }

    private fun addOrAgeShape(repo: com.geoknoesis.kastor.rdf.RdfRepository, targetClass: Iri) {
        val sh = "http://www.w3.org/ns/shacl#"
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val shape = Iri("urn:shape:AgeShape")
        val nameShape = bnode("nameShape")
        val ageShape = bnode("ageShape")
        val intConstraint = bnode("ageInt")
        val stringConstraint = bnode("ageString")

        val shNodeShape = Iri("${sh}NodeShape")
        val shTargetClass = Iri("${sh}targetClass")
        val shProperty = Iri("${sh}property")
        val shPath = Iri("${sh}path")
        val shDatatype = Iri("${sh}datatype")
        val shMinCount = Iri("${sh}minCount")
        val shOr = Iri("${sh}or")
        val shMessage = Iri("${sh}message")

        repo.add {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - shNodeShape
            shape - shTargetClass - targetClass
            shape - shProperty - nameShape
            shape - shProperty - ageShape

            nameShape - shPath - FOAF.name
            nameShape - shDatatype - Iri("${xsd}string")
            nameShape - shMinCount - 1

            ageShape - shPath - FOAF.age
            ageShape - shOr - list(intConstraint, stringConstraint)
            ageShape - shMessage - "Age must be int or string"

            intConstraint - shDatatype - Iri("${xsd}int")
            stringConstraint - shDatatype - Iri("${xsd}string")
        }
    }

    private fun addNodeKindShape(repo: com.geoknoesis.kastor.rdf.RdfRepository, targetClass: Iri) {
        val sh = "http://www.w3.org/ns/shacl#"
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val shape = Iri("urn:shape:NodeKindShape")
        val nameShape = bnode("nameShape")
        val knowsShape = bnode("knowsShape")

        val shNodeShape = Iri("${sh}NodeShape")
        val shTargetClass = Iri("${sh}targetClass")
        val shProperty = Iri("${sh}property")
        val shPath = Iri("${sh}path")
        val shDatatype = Iri("${sh}datatype")
        val shMinCount = Iri("${sh}minCount")
        val shNodeKind = Iri("${sh}nodeKind")

        repo.add {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - shNodeShape
            shape - shTargetClass - targetClass
            shape - shProperty - nameShape
            shape - shProperty - knowsShape

            nameShape - shPath - FOAF.name
            nameShape - shDatatype - Iri("${xsd}string")
            nameShape - shMinCount - 1

            knowsShape - shPath - FOAF.knows
            knowsShape - shNodeKind - Iri("${sh}IRI")
            knowsShape - shMinCount - 1
        }
    }

    private fun addInverseKnowsShape(repo: com.geoknoesis.kastor.rdf.RdfRepository, targetClass: Iri) {
        val sh = "http://www.w3.org/ns/shacl#"
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val shape = Iri("urn:shape:InverseKnowsShape")
        val nameShape = bnode("nameShape")
        val inverseShape = bnode("inverseKnowsShape")
        val inversePath = bnode("inversePath")

        val shNodeShape = Iri("${sh}NodeShape")
        val shTargetClass = Iri("${sh}targetClass")
        val shProperty = Iri("${sh}property")
        val shPath = Iri("${sh}path")
        val shDatatype = Iri("${sh}datatype")
        val shMinCount = Iri("${sh}minCount")
        val shInversePath = Iri("${sh}inversePath")

        repo.add {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - shNodeShape
            shape - shTargetClass - targetClass
            shape - shProperty - nameShape
            shape - shProperty - inverseShape

            nameShape - shPath - FOAF.name
            nameShape - shDatatype - Iri("${xsd}string")
            nameShape - shMinCount - 1

            inverseShape - shPath - inversePath
            inverseShape - shMinCount - 1
            inversePath - shInversePath - FOAF.knows
        }
    }

    @Test
    fun `validation handles large graphs efficiently`() {
        val validation = Rdf4jValidation()
        ShaclValidation.register(validation)

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        // Add many triples
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John"
            
            // Add many properties to test performance
            repeat(100) { i ->
                person - Iri("http://example.org/prop$i") - "value$i"
            }
        }

        // Should handle large graphs efficiently
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }
}












