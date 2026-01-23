package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ShaclDslTest {
    
    @Test
    fun `test basic node shape with target class`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/PersonShape") {
                targetClass("http://example.org/Person")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.subject == Iri("http://example.org/PersonShape") &&
            it.predicate == SHACL.targetClass &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test node shape with property constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/PersonShape") {
                targetClass(FOAF.Person)
                
                property(FOAF.name) {
                    minCount = 1
                    maxCount = 1
                    datatype = XSD.string
                    minLength = 1
                    maxLength = 100
                }
                
                property(FOAF.age) {
                    minCount = 0
                    maxCount = 1
                    datatype = XSD.integer
                    minInclusive = 0.0
                    maxInclusive = 150.0
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        
        // Check that property shapes were created
        val propertyShapes = triples.filter { 
            it.predicate == SHACL.property 
        }
        assertEquals(2, propertyShapes.size)
        
        // Check minCount constraint
        assertTrue(triples.any {
            it.predicate == SHACL.minCount && 
            it.obj == 1.toLiteral()
        })
        
        // Check datatype constraint
        assertTrue(triples.any {
            it.predicate == SHACL.datatype && 
            it.obj == XSD.string
        })
    }
    
    @Test
    fun `test string constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/EmailShape") {
                property("http://example.org/email") {
                    pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
                    flags = "i"
                    minLength = 5
                    maxLength = 100
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.pattern })
        assertTrue(triples.any { it.predicate == SHACL.flags })
        assertTrue(triples.any { it.predicate == SHACL.minLength })
        assertTrue(triples.any { it.predicate == SHACL.maxLength })
    }
    
    @Test
    fun `test numeric constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/PriceShape") {
                property("http://example.org/price") {
                    datatype = XSD.decimal
                    minExclusive = 0.0
                    maxInclusive = 1000.0
                    totalDigits = 10
                    fractionDigits = 2
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.minExclusive })
        assertTrue(triples.any { it.predicate == SHACL.maxInclusive })
        assertTrue(triples.any { it.predicate == SHACL.totalDigits })
        assertTrue(triples.any { it.predicate == SHACL.fractionDigits })
    }
    
    @Test
    fun `test value constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/StatusShape") {
                property("http://example.org/status") {
                    `in`("active", "inactive", "pending")
                }
                
                property("http://example.org/type") {
                    hasValue("http://example.org/Type1")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.`in` })
        assertTrue(triples.any { it.predicate == SHACL.hasValue })
    }
    
    @Test
    fun `test node kind constraint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ResourceShape") {
                property("http://example.org/resource") {
                    nodeKind = NodeKind.IRI
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.nodeKind && 
            it.obj == SHACL.IRI
        })
    }
    
    @Test
    fun `test class constraint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/PersonShape") {
                property(FOAF.knows) {
                    `class` = FOAF.Person
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.`class` && 
            it.obj == FOAF.Person
        })
    }
    
    @Test
    fun `test logical constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ComplexShape") {
                and {
                    property("http://example.org/name") {
                        minCount = 1
                    }
                    property("http://example.org/email") {
                        minCount = 1
                    }
                }
                
                or {
                    property("http://example.org/phone") {
                        minCount = 1
                    }
                    property("http://example.org/mobile") {
                        minCount = 1
                    }
                }
                
                not {
                    property("http://example.org/secret") {
                        minCount = 1
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.and })
        assertTrue(triples.any { it.predicate == SHACL.or })
        assertTrue(triples.any { it.predicate == SHACL.not })
    }
    
    @Test
    fun `test qualified value shape`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ReviewShape") {
                property("http://example.org/review") {
                    qualifiedValueShape {
                        property("http://example.org/rating") {
                            minInclusive = 1.0
                            maxInclusive = 5.0
                        }
                    }
                    qualifiedMinCount = 1
                    qualifiedMaxCount = 10
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.qualifiedValueShape })
        assertTrue(triples.any { it.predicate == SHACL.qualifiedMinCount })
        assertTrue(triples.any { it.predicate == SHACL.qualifiedMaxCount })
    }
    
    @Test
    fun `test value comparison constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ComparisonShape") {
                property("http://example.org/startDate") {
                    lessThan("http://example.org/endDate")
                }
                
                property("http://example.org/name1") {
                    equals("http://example.org/name2")
                }
                
                property("http://example.org/type1") {
                    disjoint("http://example.org/type2")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.lessThan })
        assertTrue(triples.any { it.predicate == SHACL.equals })
        assertTrue(triples.any { it.predicate == SHACL.disjoint })
    }
    
    @Test
    fun `test closed shape`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ClosedShape") {
                closed(true)
                ignoredProperties("http://example.org/allowed1", "http://example.org/allowed2")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.closed && 
            it.obj == true.toLiteral()
        })
        assertTrue(triples.any { it.predicate == SHACL.ignoredProperties })
    }
    
    @Test
    fun `test deactivated shape`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/DeactivatedShape") {
                deactivated(true)
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.deactivated && 
            it.obj == true.toLiteral()
        })
    }
    
    @Test
    fun `test message and severity`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/MessageShape") {
                message("This is a custom message")
                severity(Severity.Warning)
                
                property("http://example.org/prop") {
                    message("Property message")
                    severity(Severity.Info)
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.message })
        assertTrue(triples.any { it.predicate == SHACL.severity })
    }
    
    @Test
    fun `test language constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/LangShape") {
                property("http://example.org/label") {
                    languageIn = listOf("en", "fr", "de")
                    uniqueLang = true
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.languageIn })
        assertTrue(triples.any { 
            it.predicate == SHACL.uniqueLang && 
            it.obj == true.toLiteral()
        })
    }
    
    @Test
    fun `test nested node constraint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ParentShape") {
                property("http://example.org/child") {
                    node {
                        property("http://example.org/name") {
                            minCount = 1
                        }
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.node })
    }
    
    @Test
    fun `test prefixes`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            
            nodeShape("ex:Shape") {
                targetClass("ex:Class")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.subject == Iri("http://example.org/Shape")
        })
    }
    
    @Test
    fun `test multiple node shapes`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/Shape1") {
                targetClass("http://example.org/Class1")
            }
            
            nodeShape("http://example.org/Shape2") {
                targetClass("http://example.org/Class2")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        val shapes = triples.filter { 
            it.predicate == RDF.type && 
            it.obj == SHACL.NodeShape
        }
        assertEquals(2, shapes.size)
    }
    
    @Test
    fun `test property metadata`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/Shape") {
                property("http://example.org/prop") {
                    name("Property Name")
                    description("Property description")
                    order(1)
                    group("http://example.org/group")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.name })
        assertTrue(triples.any { it.predicate == SHACL.description })
        assertTrue(triples.any { it.predicate == SHACL.order })
        assertTrue(triples.any { it.predicate == SHACL.group })
    }
    
    // ========== Additional Comprehensive Test Coverage ==========
    
    @Test
    fun `test all node kinds`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/NodeKindShape") {
                property("http://example.org/iri") {
                    nodeKind = NodeKind.IRI
                }
                property("http://example.org/bnode") {
                    nodeKind = NodeKind.BlankNode
                }
                property("http://example.org/literal") {
                    nodeKind = NodeKind.Literal
                }
                property("http://example.org/bnodeOrIri") {
                    nodeKind = NodeKind.BlankNodeOrIRI
                }
                property("http://example.org/bnodeOrLiteral") {
                    nodeKind = NodeKind.BlankNodeOrLiteral
                }
                property("http://example.org/iriOrLiteral") {
                    nodeKind = NodeKind.IRIOrLiteral
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertEquals(6, triples.count { it.predicate == SHACL.nodeKind })
        assertTrue(triples.any { it.obj == SHACL.IRI })
        assertTrue(triples.any { it.obj == SHACL.BlankNode })
        assertTrue(triples.any { it.obj == SHACL.Literal })
        assertTrue(triples.any { it.obj == SHACL.BlankNodeOrIRI })
        assertTrue(triples.any { it.obj == SHACL.BlankNodeOrLiteral })
        assertTrue(triples.any { it.obj == SHACL.IRIOrLiteral })
    }
    
    @Test
    fun `test all severity levels`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/SeverityShape") {
                severity(Severity.Violation)
                property("http://example.org/warning") {
                    severity(Severity.Warning)
                }
                property("http://example.org/info") {
                    severity(Severity.Info)
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.severity && it.obj == SHACL.Violation 
        })
        assertTrue(triples.any { 
            it.predicate == SHACL.severity && it.obj == SHACL.Warning 
        })
        assertTrue(triples.any { 
            it.predicate == SHACL.severity && it.obj == SHACL.Info 
        })
    }
    
    @Test
    fun `test target variations`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/TargetShape") {
                targetClass("http://example.org/Class")
                targetNode(Iri("http://example.org/node1"))
                targetNode("http://example.org/node2")
                targetObjectsOf("http://example.org/prop1")
                targetSubjectsOf("http://example.org/prop2")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.targetClass })
        assertEquals(2, triples.count { it.predicate == SHACL.targetNode })
        assertTrue(triples.any { it.predicate == SHACL.targetObjectsOf })
        assertTrue(triples.any { it.predicate == SHACL.targetSubjectsOf })
    }
    
    @Test
    fun `test hasValue with different types`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/HasValueShape") {
                property("http://example.org/string") {
                    hasValue("test")
                }
                property("http://example.org/int") {
                    hasValue(42)
                }
                property("http://example.org/double") {
                    hasValue(3.14)
                }
                property("http://example.org/iri") {
                    hasValue(Iri("http://example.org/value"))
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertEquals(4, triples.count { it.predicate == SHACL.hasValue })
    }
    
    @Test
    fun `test in constraint with different types`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/InShape") {
                property("http://example.org/strings") {
                    `in`("value1", "value2", "value3")
                }
                property("http://example.org/ints") {
                    `in`(1, 2, 3)
                }
                property("http://example.org/doubles") {
                    `in`(1.1, 2.2, 3.3)
                }
                property("http://example.org/iris") {
                    `in`(listOf(Iri("http://example.org/v1"), Iri("http://example.org/v2")))
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertEquals(4, triples.count { it.predicate == SHACL.`in` })
    }
    
    @Test
    fun `test complex logical constraints with shape references`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/Shape1") {
                targetClass("http://example.org/Class1")
            }
            
            nodeShape("http://example.org/Shape2") {
                targetClass("http://example.org/Class2")
            }
            
            nodeShape("http://example.org/ComplexShape") {
                and(listOf(
                    Iri("http://example.org/Shape1"),
                    Iri("http://example.org/Shape2")
                ))
                or(listOf(
                    Iri("http://example.org/Shape1"),
                    Iri("http://example.org/Shape2")
                ))
                not(Iri("http://example.org/Shape1"))
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.and })
        assertTrue(triples.any { it.predicate == SHACL.or })
        assertTrue(triples.any { it.predicate == SHACL.not })
    }
    
    @Test
    fun `test xone constraint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/XoneShape") {
                xone {
                    property("http://example.org/option1") {
                        minCount = 1
                    }
                    property("http://example.org/option2") {
                        minCount = 1
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.xone })
    }
    
    @Test
    fun `test qualified value shapes disjoint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/QualifiedShape") {
                property("http://example.org/prop") {
                    qualifiedValueShape("http://example.org/Shape1")
                    qualifiedValueShapesDisjoint = true
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.qualifiedValueShape })
        assertTrue(triples.any { it.predicate == SHACL.qualifiedValueShapesDisjoint })
    }
    
    @Test
    fun `test node constraint with shape reference`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/NodeRefShape") {
                property("http://example.org/prop") {
                    node("http://example.org/ReferencedShape")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.node && 
            it.obj == Iri("http://example.org/ReferencedShape")
        })
    }
    
    @Test
    fun `test lessThanOrEquals constraint`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/LessThanShape") {
                property("http://example.org/start") {
                    lessThanOrEquals("http://example.org/end")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.lessThanOrEquals })
    }
    
    @Test
    fun `test message with language tag`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/MessageShape") {
                message("English message", "en")
                message("French message", "fr")
                
                property("http://example.org/prop") {
                    message("Property message", "en")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        val messages = triples.filter { it.predicate == SHACL.message }
        assertTrue(messages.size >= 3)
    }
    
    @Test
    fun `test name and description with language tags`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/MetadataShape") {
                property("http://example.org/prop") {
                    name("Property Name", "en")
                    description("Property description", "en")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.name })
        assertTrue(triples.any { it.predicate == SHACL.description })
    }
    
    @Test
    fun `test standalone property shape`() {
        val shapesGraph = shacl {
            propertyShape("http://example.org/StandaloneProperty") {
                path = Iri("http://example.org/prop")
                minCount = 1
                datatype = XSD.string
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.subject == Iri("http://example.org/StandaloneProperty") &&
            it.predicate == RDF.type &&
            it.obj == SHACL.PropertyShape
        })
        assertTrue(triples.any { 
            it.predicate == SHACL.path &&
            it.obj == Iri("http://example.org/prop")
        })
    }
    
    @Test
    fun `test Rdf dot shacl function`() {
        val shapesGraph = Rdf.shacl {
            nodeShape("http://example.org/RdfShape") {
                targetClass("http://example.org/Class")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.subject == Iri("http://example.org/RdfShape") &&
            it.predicate == SHACL.targetClass
        })
    }
    
    @Test
    fun `test multiple properties with same constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/MultiPropShape") {
                property("http://example.org/prop1") {
                    minCount = 1
                    datatype = XSD.string
                }
                property("http://example.org/prop2") {
                    minCount = 1
                    datatype = XSD.string
                }
                property("http://example.org/prop3") {
                    minCount = 1
                    datatype = XSD.string
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        val propertyShapes = triples.filter { it.predicate == SHACL.property }
        assertEquals(3, propertyShapes.size)
        
        val minCounts = triples.filter { it.predicate == SHACL.minCount }
        assertEquals(3, minCounts.size)
    }
    
    @Test
    fun `test complex nested structure`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/OuterShape") {
                targetClass("http://example.org/OuterClass")
                
                property("http://example.org/inner") {
                    node {
                        targetClass("http://example.org/InnerClass")
                        property("http://example.org/nested") {
                            minCount = 1
                            datatype = XSD.string
                        }
                    }
                }
                
                and {
                    property("http://example.org/req1") {
                        minCount = 1
                    }
                    property("http://example.org/req2") {
                        minCount = 1
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.targetClass })
        assertTrue(triples.any { it.predicate == SHACL.node })
        assertTrue(triples.any { it.predicate == SHACL.and })
    }
    
    @Test
    fun `test all numeric constraint combinations`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/NumericShape") {
                property("http://example.org/inclusive") {
                    minInclusive = 0.0
                    maxInclusive = 100.0
                }
                property("http://example.org/exclusive") {
                    minExclusive = 0.0
                    maxExclusive = 100.0
                }
                property("http://example.org/mixed") {
                    minInclusive = 0.0
                    maxExclusive = 100.0
                }
                property("http://example.org/precision") {
                    totalDigits = 10
                    fractionDigits = 2
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.minInclusive })
        assertTrue(triples.any { it.predicate == SHACL.maxInclusive })
        assertTrue(triples.any { it.predicate == SHACL.minExclusive })
        assertTrue(triples.any { it.predicate == SHACL.maxExclusive })
        assertTrue(triples.any { it.predicate == SHACL.totalDigits })
        assertTrue(triples.any { it.predicate == SHACL.fractionDigits })
    }
    
    @Test
    fun `test property deactivation`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/DeactivatedPropShape") {
                property("http://example.org/prop") {
                    minCount = 1
                    deactivated(true)
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.deactivated && 
            it.obj == true.toLiteral()
        })
    }
    
    @Test
    fun `test closed shape with multiple ignored properties`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/ClosedShape") {
                closed(true)
                ignoredProperties(
                    "http://example.org/allowed1",
                    "http://example.org/allowed2",
                    "http://example.org/allowed3"
                )
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        val ignored = triples.filter { it.predicate == SHACL.ignoredProperties }
        assertEquals(3, ignored.size)
    }
    
    @Test
    fun `test prefix resolution in shapes`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            nodeShape("ex:Shape") {
                targetClass("foaf:Person")
                property("foaf:name") {
                    minCount = 1
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.subject == Iri("http://example.org/Shape")
        })
        assertTrue(triples.any { 
            it.obj == FOAF.Person
        })
    }
    
    @Test
    fun `test empty shape graph`() {
        val shapesGraph = shacl {
            // Empty shape graph
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.isEmpty())
    }
    
    @Test
    fun `test shape with only target class`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/MinimalShape") {
                targetClass("http://example.org/Class")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertEquals(2, triples.size) // type + targetClass
        assertTrue(triples.any { 
            it.predicate == RDF.type && 
            it.obj == SHACL.NodeShape
        })
        assertTrue(triples.any { 
            it.predicate == SHACL.targetClass
        })
    }
    
    @Test
    fun `test property with all string constraints`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/StringShape") {
                property("http://example.org/text") {
                    minLength = 1
                    maxLength = 100
                    pattern = "^[A-Z].*"
                    flags = "i"
                    languageIn = listOf("en", "fr")
                    uniqueLang = true
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { it.predicate == SHACL.minLength })
        assertTrue(triples.any { it.predicate == SHACL.maxLength })
        assertTrue(triples.any { it.predicate == SHACL.pattern })
        assertTrue(triples.any { it.predicate == SHACL.flags })
        assertTrue(triples.any { it.predicate == SHACL.languageIn })
        assertTrue(triples.any { it.predicate == SHACL.uniqueLang })
    }
    
    @Test
    fun `test qualified value shape with IRI reference`() {
        val shapesGraph = shacl {
            nodeShape("http://example.org/QualifiedRefShape") {
                property("http://example.org/prop") {
                    qualifiedValueShape(Iri("http://example.org/QualifiedShape"))
                    qualifiedMinCount = 2
                    qualifiedMaxCount = 5
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any { 
            it.predicate == SHACL.qualifiedValueShape &&
            it.obj == Iri("http://example.org/QualifiedShape")
        })
        assertTrue(triples.any { it.predicate == SHACL.qualifiedMinCount })
        assertTrue(triples.any { it.predicate == SHACL.qualifiedMaxCount })
    }
    
    @Test
    fun `test SHACL 12 targetWhere with nested shape`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                targetWhere {
                    property("ex:status") {
                        hasValue("active")
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.targetWhere
        })
    }
    
    @Test
    fun `test SHACL 12 targetWhere with shape reference`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                targetWhere("ex:NodeExpression")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.targetWhere &&
            it.obj == Iri("http://example.org/NodeExpression")
        })
    }
    
    @Test
    fun `test SHACL 12 explicit shape target`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                shape("ex:OtherShape")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.shape &&
            it.obj == Iri("http://example.org/OtherShape")
        })
    }
    
    @Test
    fun `test SHACL 12 singleLine constraint`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                property("ex:description") {
                    singleLine = true
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.singleLine &&
            it.obj == true.toLiteral()
        })
    }
    
    @Test
    fun `test SHACL 12 reifierShape with shape reference`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                property("ex:statement") {
                    reifierShape("ex:ReifierShape")
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.reifierShape &&
            it.obj == Iri("http://example.org/ReifierShape")
        })
    }
    
    @Test
    fun `test SHACL 12 reifierShape with nested shape`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                property("ex:statement") {
                    reifierShape {
                        property("ex:source") {
                            minCount = 1
                        }
                    }
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.reifierShape
        })
    }
    
    @Test
    fun `test SHACL 12 reificationRequired constraint`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                property("ex:statement") {
                    reificationRequired = true
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.reificationRequired &&
            it.obj == true.toLiteral()
        })
    }
    
    @Test
    fun `test SHACL 12 SPARQL constraint with SELECT query`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                sparql(configureQuery = {
                    where {
                        triple(`var`("this"), Iri("ex:status"), `var`("status"))
                        filter(`var`("status") eq "active")
                    }
                }, configureConstraint = {
                    message("Status must be active")
                })
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.sparql
        })
        assertTrue(triples.any {
            it.predicate == SHACL.select
        })
    }
    
    @Test
    fun `test SHACL 12 SPARQL constraint with ASK query`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                sparqlAsk(configureQuery = {
                    where {
                        triple(`var`("this"), Iri("ex:valid"), true.toLiteral())
                    }
                }, configureConstraint = {
                    message("Resource must be valid")
                })
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.sparql
        })
        assertTrue(triples.any {
            it.predicate == SHACL.ask
        })
    }
    
    @Test
    fun `test SHACL 12 SPARQL constraint with prefixes and parameters`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                sparql(configureQuery = {
                    where {
                        triple(`var`("this"), `var`("p"), `var`("o"))
                    }
                }, configureConstraint = {
                    prefixes {
                        put("foaf", "http://xmlns.com/foaf/0.1/")
                    }
                    parameter("ex:minValue")
                    labelTemplate("Value must be at least {?minValue}")
                })
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.prefixes
        })
        assertTrue(triples.any {
            it.predicate == SHACL.parameter
        })
        assertTrue(triples.any {
            it.predicate == SHACL.labelTemplate
        })
    }
    
    @Test
    fun `test SHACL 12 targetWhere with SelectExpression`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                targetWhereSelect(configureQuery = {
                    where {
                        triple(`var`("node"), Iri("ex:type"), Iri("ex:Active"))
                    }
                })
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.targetWhere
        })
        assertTrue(triples.any {
            it.predicate == SHACL.selectExpression
        })
    }
    
    @Test
    fun `test SHACL 12 targetWhere with SPARQL expression`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                targetWhereExpr("?node ex:status ex:active")
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.targetWhere
        })
        assertTrue(triples.any {
            it.predicate == SHACL.exprExpression
        })
    }
    
    @Test
    fun `test SHACL 12 SPARQL constraint on property shape`() {
        val shapesGraph = shacl {
            prefixes {
                put("ex", "http://example.org/")
            }
            nodeShape("ex:Shape") {
                property("ex:value") {
                    sparql(configureQuery = {
                        where {
                            triple(`var`("value"), Iri("ex:valid"), true.toLiteral())
                        }
                    }, configureConstraint = {
                        message("Property value must be valid")
                    })
                }
            }
        }
        
        val triples = shapesGraph.getTriples().toList()
        assertTrue(triples.any {
            it.predicate == SHACL.sparql
        })
    }
}

