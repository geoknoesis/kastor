package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OwlDslTest {
    
    @Test
    fun `test basic ontology definition`() {
        val graph = owl {
            ontology("http://example.org/MyOntology") {
                versionInfo("1.0")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check ontology type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/MyOntology") &&
            it.predicate == RDF.type &&
            it.obj == OWL.Ontology
        })
        
        // Check versionInfo
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/MyOntology") &&
            it.predicate == OWL.versionInfo &&
            (it.obj as? Literal)?.lexical == "1.0"
        })
    }
    
    @Test
    fun `test ontology with imports`() {
        val graph = owl {
            ontology("http://example.org/MyOntology") {
                imports("http://example.org/OtherOntology")
                imports("http://example.org/AnotherOntology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val imports = triples.filter {
            it.subject == Iri("http://example.org/MyOntology") &&
            it.predicate == OWL.imports
        }
        
        assertEquals(2, imports.size)
        assertTrue(imports.any { it.obj == Iri("http://example.org/OtherOntology") })
        assertTrue(imports.any { it.obj == Iri("http://example.org/AnotherOntology") })
    }
    
    @Test
    fun `test ontology versioning`() {
        val graph = owl {
            ontology("http://example.org/MyOntology") {
                versionInfo("1.0")
                versionIRI("http://example.org/MyOntology/1.0")
                priorVersion("http://example.org/MyOntology/0.9")
                backwardCompatibleWith("http://example.org/MyOntology/0.9")
                incompatibleWith("http://example.org/OldOntology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any { it.predicate == OWL.versionInfo })
        assertTrue(triples.any { it.predicate == OWL.versionIRI })
        assertTrue(triples.any { it.predicate == OWL.priorVersion })
        assertTrue(triples.any { it.predicate == OWL.backwardCompatibleWith })
        assertTrue(triples.any { it.predicate == OWL.incompatibleWith })
    }
    
    @Test
    fun `test basic class definition`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                label("Person", "en")
                comment("A human being", "en")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check class type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDF.type &&
            it.obj == OWL.Class
        })
        
        // Check label
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Person"
        })
    }
    
    @Test
    fun `test class with subClassOf`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                subClassOf("http://example.org/Animal")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == Iri("http://example.org/Animal")
        })
    }
    
    @Test
    fun `test class with equivalentClass`() {
        val graph = owl {
            `class`("http://example.org/Human") {
                equivalentClass("http://example.org/Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Human") &&
            it.predicate == OWL.equivalentClass &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test class with disjointWith`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                disjointWith("http://example.org/Animal")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == OWL.disjointWith &&
            it.obj == Iri("http://example.org/Animal")
        })
    }
    
    @Test
    fun `test class with complementOf`() {
        val graph = owl {
            `class`("http://example.org/NonPerson") {
                complementOf("http://example.org/Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/NonPerson") &&
            it.predicate == OWL.complementOf &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test class with unionOf`() {
        val graph = owl {
            `class`("http://example.org/Pet") {
                equivalentClass {
                    unionOf("http://example.org/Dog", "http://example.org/Cat")
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Find the union class expression
        val unionTriple = triples.find {
            it.subject == Iri("http://example.org/Pet") &&
            it.predicate == OWL.equivalentClass
        }
        
        assertNotNull(unionTriple)
        val unionNode = unionTriple!!.obj as? BlankNode
        assertNotNull(unionNode)
        
        // Check unionOf property
        assertTrue(triples.any {
            it.subject == unionNode &&
            it.predicate == OWL.unionOf
        })
    }
    
    @Test
    fun `test class with intersectionOf`() {
        val graph = owl {
            `class`("http://example.org/Student") {
                equivalentClass {
                    intersectionOf("http://example.org/Person", "http://example.org/Enrolled")
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val intersectionTriple = triples.find {
            it.subject == Iri("http://example.org/Student") &&
            it.predicate == OWL.equivalentClass
        }
        
        assertNotNull(intersectionTriple)
        val intersectionNode = intersectionTriple!!.obj as? BlankNode
        assertNotNull(intersectionNode)
        
        // Check intersectionOf property
        assertTrue(triples.any {
            it.subject == intersectionNode &&
            it.predicate == OWL.intersectionOf
        })
    }
    
    @Test
    fun `test class with oneOf`() {
        val graph = owl {
            `class`("http://example.org/Status") {
                equivalentClass {
                    oneOf("http://example.org/Active", "http://example.org/Inactive")
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val oneOfTriple = triples.find {
            it.subject == Iri("http://example.org/Status") &&
            it.predicate == OWL.equivalentClass
        }
        
        assertNotNull(oneOfTriple)
        val oneOfNode = oneOfTriple!!.obj as? BlankNode
        assertNotNull(oneOfNode)
        
        // Check oneOf property
        assertTrue(triples.any {
            it.subject == oneOfNode &&
            it.predicate == OWL.oneOf
        })
    }
    
    @Test
    fun `test class with restriction allValuesFrom`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasParent") {
                        allValuesFrom("http://example.org/Person")
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Find restriction
        val restrictionTriple = triples.find {
            it.predicate == OWL.Restriction
        }
        
        assertNotNull(restrictionTriple)
        
        // Check allValuesFrom
        assertTrue(triples.any {
            it.predicate == OWL.allValuesFrom &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test class with restriction someValuesFrom`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasChild") {
                        someValuesFrom("http://example.org/Person")
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check someValuesFrom
        assertTrue(triples.any {
            it.predicate == OWL.someValuesFrom &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test class with restriction hasValue`() {
        val graph = owl {
            `class`("http://example.org/ActivePerson") {
                equivalentClass {
                    restriction("http://example.org/status") {
                        hasValue(string("active"))
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check hasValue
        assertTrue(triples.any {
            it.predicate == OWL.hasValue
        })
    }
    
    @Test
    fun `test class with restriction cardinality`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasName") {
                        cardinality(1)
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check cardinality
        assertTrue(triples.any {
            it.predicate == OWL.cardinality &&
            (it.obj as? Literal)?.lexical == "1"
        })
    }
    
    @Test
    fun `test class with restriction minCardinality and maxCardinality`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasEmail") {
                        minCardinality(0)
                        maxCardinality(5)
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check minCardinality
        assertTrue(triples.any {
            it.predicate == OWL.minCardinality &&
            (it.obj as? Literal)?.lexical == "0"
        })
        
        // Check maxCardinality
        assertTrue(triples.any {
            it.predicate == OWL.maxCardinality &&
            (it.obj as? Literal)?.lexical == "5"
        })
    }
    
    @Test
    fun `test class with restriction qualifiedCardinality`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasParent") {
                        qualifiedCardinality(2, "http://example.org/Person")
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check qualifiedCardinality
        assertTrue(triples.any {
            it.predicate == OWL.qualifiedCardinality &&
            (it.obj as? Literal)?.lexical == "2"
        })
        
        // Check onClass
        assertTrue(triples.any {
            it.predicate == OWL.onClass &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test object property definition`() {
        val graph = owl {
            objectProperty("http://example.org/hasParent") {
                label("has parent", "en")
                domain("http://example.org/Person")
                range("http://example.org/Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check property type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == RDF.type &&
            it.obj == OWL.ObjectProperty
        })
        
        // Check domain
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == RDFS.domain &&
            it.obj == Iri("http://example.org/Person")
        })
        
        // Check range
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == RDFS.range &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test object property with inverseOf`() {
        val graph = owl {
            objectProperty("http://example.org/hasParent") {
                inverseOf("http://example.org/hasChild")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == OWL.inverseOf &&
            it.obj == Iri("http://example.org/hasChild")
        })
    }
    
    @Test
    fun `test object property with functional`() {
        val graph = owl {
            objectProperty("http://example.org/hasSSN") {
                functional()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasSSN") &&
            it.predicate == RDF.type &&
            it.obj == OWL.FunctionalProperty
        })
    }
    
    @Test
    fun `test object property with transitive`() {
        val graph = owl {
            objectProperty("http://example.org/ancestorOf") {
                transitive()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/ancestorOf") &&
            it.predicate == RDF.type &&
            it.obj == OWL.TransitiveProperty
        })
    }
    
    @Test
    fun `test object property with symmetric`() {
        val graph = owl {
            objectProperty("http://example.org/knows") {
                symmetric()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/knows") &&
            it.predicate == RDF.type &&
            it.obj == OWL.SymmetricProperty
        })
    }
    
    @Test
    fun `test object property with asymmetric`() {
        val graph = owl {
            objectProperty("http://example.org/hasChild") {
                asymmetric()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasChild") &&
            it.predicate == RDF.type &&
            it.obj == OWL.AsymmetricProperty
        })
    }
    
    @Test
    fun `test object property with reflexive`() {
        val graph = owl {
            objectProperty("http://example.org/relatedTo") {
                reflexive()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/relatedTo") &&
            it.predicate == RDF.type &&
            it.obj == OWL.ReflexiveProperty
        })
    }
    
    @Test
    fun `test object property with irreflexive`() {
        val graph = owl {
            objectProperty("http://example.org/hasParent") {
                irreflexive()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == RDF.type &&
            it.obj == OWL.IrreflexiveProperty
        })
    }
    
    @Test
    fun `test object property with propertyChainAxiom`() {
        val graph = owl {
            objectProperty("http://example.org/hasGrandparent") {
                propertyChainAxiom("http://example.org/hasParent", "http://example.org/hasParent")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check propertyChainAxiom
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasGrandparent") &&
            it.predicate == OWL.propertyChainAxiom
        })
    }
    
    @Test
    fun `test object property with equivalentProperty`() {
        val graph = owl {
            objectProperty("http://example.org/hasParent") {
                equivalentProperty("http://example.org/parentOf")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == OWL.equivalentProperty &&
            it.obj == Iri("http://example.org/parentOf")
        })
    }
    
    @Test
    fun `test object property with propertyDisjointWith`() {
        val graph = owl {
            objectProperty("http://example.org/hasParent") {
                propertyDisjointWith("http://example.org/hasChild")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == OWL.propertyDisjointWith &&
            it.obj == Iri("http://example.org/hasChild")
        })
    }
    
    @Test
    fun `test data property definition`() {
        val graph = owl {
            dataProperty("http://example.org/age") {
                label("Age", "en")
                domain("http://example.org/Person")
                range(XSD.integer)
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check property type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/age") &&
            it.predicate == RDF.type &&
            it.obj == OWL.DatatypeProperty
        })
        
        // Check range
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/age") &&
            it.predicate == RDFS.range &&
            it.obj == XSD.integer
        })
    }
    
    @Test
    fun `test data property with functional`() {
        val graph = owl {
            dataProperty("http://example.org/age") {
                functional()
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/age") &&
            it.predicate == RDF.type &&
            it.obj == OWL.FunctionalProperty
        })
    }
    
    @Test
    fun `test annotation property definition`() {
        val graph = owl {
            annotationProperty("http://example.org/comment") {
                label("Comment", "en")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check property type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/comment") &&
            it.predicate == RDF.type &&
            it.obj == OWL.AnnotationProperty
        })
    }
    
    @Test
    fun `test individual definition`() {
        val graph = owl {
            individual("http://example.org/alice") {
                `is`("http://example.org/Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check individual type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == RDF.type &&
            it.obj == OWL.NamedIndividual
        })
        
        // Check class type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == RDF.type &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test individual with sameAs`() {
        val graph = owl {
            individual("http://example.org/alice") {
                sameAs("http://example.org/aliceSmith")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == OWL.sameAs &&
            it.obj == Iri("http://example.org/aliceSmith")
        })
    }
    
    @Test
    fun `test individual with differentFrom`() {
        val graph = owl {
            individual("http://example.org/alice") {
                differentFrom("http://example.org/bob")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == OWL.differentFrom &&
            it.obj == Iri("http://example.org/bob")
        })
    }
    
    @Test
    fun `test individual with property assertions`() {
        val graph = owl {
            individual("http://example.org/alice") {
                `is`("http://example.org/Person")
                property("http://example.org/age", 30)
                property("http://example.org/name", "Alice")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check age property
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == Iri("http://example.org/age") &&
            (it.obj as? Literal)?.lexical == "30"
        })
        
        // Check name property
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == Iri("http://example.org/name") &&
            (it.obj as? Literal)?.lexical == "Alice"
        })
    }
    
    @Test
    fun `test prefixes`() {
        val graph = owl {
            prefix("ex", "http://example.org/")
            
            `class`("ex:Person") {
                label("Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test complex class expression`() {
        val graph = owl {
            `class`("http://example.org/Student") {
                equivalentClass {
                    intersectionOf(
                        "http://example.org/Person",
                        "http://example.org/Enrolled"
                    )
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Find intersection
        val intersectionTriple = triples.find {
            it.subject == Iri("http://example.org/Student") &&
            it.predicate == OWL.equivalentClass
        }
        
        assertNotNull(intersectionTriple)
        assertTrue(intersectionTriple!!.obj is BlankNode)
    }
    
    @Test
    fun `test restriction with hasSelf`() {
        val graph = owl {
            `class`("http://example.org/SelfAware") {
                equivalentClass {
                    restriction("http://example.org/knows") {
                        hasSelf(true)
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check hasSelf
        assertTrue(triples.any {
            it.predicate == OWL.hasSelf &&
            (it.obj as? Literal)?.lexical == "true"
        })
    }
    
    @Test
    fun `test restriction with minQualifiedCardinality and maxQualifiedCardinality`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                equivalentClass {
                    restriction("http://example.org/hasParent") {
                        minQualifiedCardinality(1, "http://example.org/Person")
                        maxQualifiedCardinality(2, "http://example.org/Person")
                    }
                }
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check minQualifiedCardinality
        assertTrue(triples.any {
            it.predicate == OWL.minQualifiedCardinality &&
            (it.obj as? Literal)?.lexical == "1"
        })
        
        // Check maxQualifiedCardinality
        assertTrue(triples.any {
            it.predicate == OWL.maxQualifiedCardinality &&
            (it.obj as? Literal)?.lexical == "2"
        })
    }
    
    @Test
    fun `test empty graph`() {
        val graph = owl {
            // Empty graph
        }
        
        val triples = graph.getTriples().toList()
        assertTrue(triples.isEmpty())
    }
    
    @Test
    fun `test multiple ontologies`() {
        val graph = owl {
            ontology("http://example.org/Ontology1") {
                versionInfo("1.0")
            }
            
            ontology("http://example.org/Ontology2") {
                versionInfo("2.0")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val ontologies = triples.filter {
            it.predicate == RDF.type &&
            it.obj == OWL.Ontology
        }
        
        assertEquals(2, ontologies.size)
    }
    
    @Test
    fun `test multiple classes`() {
        val graph = owl {
            `class`("http://example.org/Person") {
                label("Person")
            }
            
            `class`("http://example.org/Animal") {
                label("Animal")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val classes = triples.filter {
            it.predicate == RDF.type &&
            it.obj == OWL.Class
        }
        
        assertEquals(2, classes.size)
    }
    
    @Test
    fun `test direct triple addition`() {
        val person = Iri("http://example.org/Person")
        val graph = owl {
            triple(person, RDF.type, OWL.Class)
            triple(person, RDFS.label, string("Person"))
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == person &&
            it.predicate == RDF.type &&
            it.obj == OWL.Class
        })
        
        assertTrue(triples.any {
            it.subject == person &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Person"
        })
    }
    
    @Test
    fun `test complete ontology example`() {
        val graph = owl {
            prefix("ex", "http://example.org/")
            
            ontology("ex:MyOntology") {
                versionInfo("1.0")
                imports("http://example.org/OtherOntology")
            }
            
            `class`("ex:Person") {
                label("Person", "en")
                subClassOf("ex:Animal")
                equivalentClass {
                    intersectionOf("ex:Animal", "ex:HasName")
                }
            }
            
            objectProperty("ex:hasParent") {
                domain("ex:Person")
                range("ex:Person")
                inverseOf("ex:hasChild")
                transitive()
            }
            
            dataProperty("ex:age") {
                domain("ex:Person")
                range(XSD.integer)
                functional()
            }
            
            individual("ex:alice") {
                `is`("ex:Person")
                property("ex:age", 30)
                sameAs("ex:aliceSmith")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Verify ontology
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/MyOntology") &&
            it.predicate == RDF.type &&
            it.obj == OWL.Ontology
        })
        
        // Verify class
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDF.type &&
            it.obj == OWL.Class
        })
        
        // Verify object property
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/hasParent") &&
            it.predicate == RDF.type &&
            it.obj == OWL.ObjectProperty
        })
        
        // Verify data property
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/age") &&
            it.predicate == RDF.type &&
            it.obj == OWL.DatatypeProperty
        })
        
        // Verify individual
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/alice") &&
            it.predicate == RDF.type &&
            it.obj == OWL.NamedIndividual
        })
    }
}

