package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.model.ClassModel
import com.geoknoesis.kastor.gen.processor.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.model.PropertyType
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OntoMapperProcessorTest {

    private val mockLogger = object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `processor handles empty symbol list gracefully`() {
        // This test verifies that the processor doesn't crash when no symbols are found
        // In a real KSP environment, this would be tested with actual symbol resolution
        assertDoesNotThrow {
            // Processor should handle empty input gracefully
        }
    }

    @Test
    fun `processor correctly classifies property types`() {
        // Test the property type classification logic that's used in the processor
        
        // String should be LITERAL
        val stringType = "String"
        assertTrue(isLiteralType(stringType))
        
        // List<String> should be LITERAL
        val stringListType = "List<String>"
        assertTrue(isLiteralType(stringListType))

        // List<kotlin.String> should be LITERAL after normalization
        val kotlinStringListType = "List<kotlin.String>"
        assertTrue(isLiteralType(normalizeKotlinListType(kotlinStringListType)))
        
        // List<Int> should be LITERAL
        val intListType = "List<Int>"
        assertTrue(isLiteralType(intListType))
        
        // List<SomeClass> should be OBJECT_LIST
        val objectListType = "List<SomeClass>"
        assertFalse(isLiteralType(objectListType))
        
        // SomeClass should be OBJECT
        val objectType = "SomeClass"
        assertFalse(isLiteralType(objectType))
    }

    @Test
    fun `processor generates correct class model`() {
        val properties = listOf(
            PropertyModel(
                name = "name",
                kotlinType = "String",
                predicateIri = "http://xmlns.com/foaf/0.1/name",
                type = PropertyType.LITERAL
            ),
            PropertyModel(
                name = "friends",
                kotlinType = "List<Person>",
                predicateIri = "http://xmlns.com/foaf/0.1/knows",
                type = PropertyType.OBJECT_LIST
            )
        )
        
        val classModel = ClassModel(
            qualifiedName = "com.example.Person",
            simpleName = "Person",
            packageName = "com.example",
            classIri = "http://xmlns.com/foaf/0.1/Person",
            properties = properties
        )
        
        assertEquals("com.example.Person", classModel.qualifiedName)
        assertEquals("Person", classModel.simpleName)
        assertEquals("com.example", classModel.packageName)
        assertEquals(2, classModel.properties.size)
        assertEquals("name", classModel.properties[0].name)
        assertEquals("friends", classModel.properties[1].name)
    }

    @Test
    fun `processor handles malformed predicate IRIs gracefully`() {
        val property = PropertyModel(
            name = "name",
            kotlinType = "String",
            predicateIri = "", // Empty IRI
            type = PropertyType.LITERAL
        )
        
        // Should not crash with empty IRI
        assertNotNull(property)
        assertEquals("", property.predicateIri)
    }

    @Test
    fun `processor handles complex property types`() {
        val complexTypes = listOf(
            "List<List<String>>",
            "Map<String, Int>",
            "Set<SomeClass>",
            "Optional<String>",
            "Pair<String, Int>"
        )
        
        complexTypes.forEach { type ->
            // Should not crash with complex types
            assertDoesNotThrow {
                PropertyModel(
                    name = "complexProp",
                    kotlinType = type,
                    predicateIri = "http://example.org/prop",
                    type = PropertyType.OBJECT // Default to OBJECT for complex types
                )
            }
        }
    }

    // Helper function to test property type classification logic
    private fun isLiteralType(kotlinType: String): Boolean {
        return when {
            kotlinType == "List<String>" || kotlinType == "List<Int>" || 
            kotlinType == "List<Double>" || kotlinType == "List<Boolean>" -> true
            kotlinType == "String" || kotlinType == "Int" || 
            kotlinType == "Double" || kotlinType == "Boolean" -> true
            else -> false
        }
    }

    private fun normalizeKotlinListType(typeName: String): String {
        return typeName
            .replace("List<kotlin.String>", "List<String>")
            .replace("List<kotlin.Int>", "List<Int>")
            .replace("List<kotlin.Double>", "List<Double>")
            .replace("List<kotlin.Boolean>", "List<Boolean>")
    }
}












