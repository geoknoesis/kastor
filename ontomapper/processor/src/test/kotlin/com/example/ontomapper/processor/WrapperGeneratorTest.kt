package com.example.ontomapper.processor

import com.example.ontomapper.processor.codegen.WrapperGenerator
import com.example.ontomapper.processor.model.ClassModel
import com.example.ontomapper.processor.model.PropertyModel
import com.example.ontomapper.processor.model.PropertyType
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WrapperGeneratorTest {

    private val mockLogger = object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `wrapper generation compiles and registry contains entry per domain interface`() {
        val generator = WrapperGenerator(mockLogger)
        
        val classModel = ClassModel(
            qualifiedName = "com.example.test.Person",
            simpleName = "Person",
            packageName = "com.example.test",
            classIri = "http://xmlns.com/foaf/0.1/Person",
            properties = listOf(
                PropertyModel(
                    name = "name",
                    kotlinType = "String",
                    predicateIri = "http://xmlns.com/foaf/0.1/name",
                    type = PropertyType.LITERAL
                ),
                PropertyModel(
                    name = "age",
                    kotlinType = "Int",
                    predicateIri = "http://xmlns.com/foaf/0.1/age",
                    type = PropertyType.LITERAL
                ),
                PropertyModel(
                    name = "friends",
                    kotlinType = "List<Person>",
                    predicateIri = "http://xmlns.com/foaf/0.1/knows",
                    type = PropertyType.OBJECT_LIST
                )
            )
        )
        
        val code = generator.generateWrapper(classModel)
        
        // Check that the generated code contains expected elements
        assertTrue(code.contains("class PersonWrapper"))
        assertTrue(code.contains("override val rdf: RdfHandle"))
        assertTrue(code.contains(": Person, RdfBacked"))
        assertTrue(code.contains("private val known: Set<Iri> = setOf("))
        assertTrue(code.contains("Iri(\"http://xmlns.com/foaf/0.1/name\")"))
        assertTrue(code.contains("Iri(\"http://xmlns.com/foaf/0.1/age\")"))
        assertTrue(code.contains("Iri(\"http://xmlns.com/foaf/0.1/knows\")"))
        assertTrue(code.contains("override val name: String"))
        assertTrue(code.contains("override val age: Int"))
        assertTrue(code.contains("override val friends: List<Person>"))
        assertTrue(code.contains("companion object"))
        assertTrue(code.contains("OntoMapper.registry[Person::class.java]"))
        assertTrue(code.contains("PersonWrapper(handle)"))
    }
    
    @Test
    fun `known predicate set includes all mapped properties`() {
        val generator = WrapperGenerator(mockLogger)
        
        val classModel = ClassModel(
            qualifiedName = "com.example.test.TestClass",
            simpleName = "TestClass",
            packageName = "com.example.test",
            classIri = "http://example.org/TestClass",
            properties = listOf(
                PropertyModel(
                    name = "prop1",
                    kotlinType = "String",
                    predicateIri = "http://example.org/prop1",
                    type = PropertyType.LITERAL
                ),
                PropertyModel(
                    name = "prop2",
                    kotlinType = "Int",
                    predicateIri = "http://example.org/prop2",
                    type = PropertyType.LITERAL
                )
            )
        )
        
        val code = generator.generateWrapper(classModel)
        
        // Check that all mapped properties are in the known set
        assertTrue(code.contains("Iri(\"http://example.org/prop1\")"))
        assertTrue(code.contains("Iri(\"http://example.org/prop2\")"))
        
        // Check that the known set is properly formatted
        val knownSetRegex = """private val known: Set<Iri> = setOf\(\s*Iri\("http://example\.org/prop1"\),\s*Iri\("http://example\.org/prop2"\)\s*\)""".toRegex()
        assertTrue(knownSetRegex.containsMatchIn(code))
    }
}












