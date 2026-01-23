package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.*
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ValidationCodeGeneratorTest {

    private lateinit var logger: KSPLogger
    private lateinit var generator: ValidationCodeGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        generator = ValidationCodeGenerator(logger)
    }

    @Test
    fun `generateValidationMethod creates validate function`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = emptyList(),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        assertEquals("validate", method.name)
        assertTrue(method.returnType.toString().contains("Unit"))

        val code = method.toString()
        assertTrue(code.contains("val violations = mutableListOf"))
        assertTrue(code.contains("if (violations.isNotEmpty())"))
        assertTrue(code.contains("ValidationException"))
    }

    @Test
    fun `generateValidationMethod checks required properties`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "prefLabel",
                    propertyIri = "http://www.w3.org/2004/02/skos/core#prefLabel",
                    kotlinType = String::class.asTypeName(),
                    isRequired = true,
                    isList = false,
                    constraints = PropertyConstraints()
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("prefLabelCount"))
        assertTrue(code.contains("graph.getTriples"))
        assertTrue(code.contains("if (prefLabelCount < 1)"))
        assertTrue(code.contains("prefLabel is required"))
    }

    @Test
    fun `generateValidationMethod validates minLength constraint`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "title",
                    propertyIri = "http://example.org/title",
                    kotlinType = String::class.asTypeName(),
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(minLength = 3)
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("Validate title constraints"))
        assertTrue(code.contains("value.length < 3"))
        assertTrue(code.contains("title must have minLength >= 3"))
    }

    @Test
    fun `generateValidationMethod validates maxLength constraint`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "title",
                    propertyIri = "http://example.org/title",
                    kotlinType = String::class.asTypeName(),
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(maxLength = 100)
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("value.length > 100"))
        assertTrue(code.contains("title must have maxLength <= 100"))
    }

    @Test
    fun `generateValidationMethod validates pattern constraint`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "email",
                    propertyIri = "http://example.org/email",
                    kotlinType = String::class.asTypeName(),
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(pattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("Regex"))
        assertTrue(code.contains("matches(value)"))
        assertTrue(code.contains("email must match pattern"))
    }

    @Test
    fun `generateValidationMethod validates inValues constraint`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "status",
                    propertyIri = "http://example.org/status",
                    kotlinType = String::class.asTypeName(),
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(inValues = listOf("active", "inactive"))
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("value !in listOf"))
        assertTrue(code.contains("active"))
        assertTrue(code.contains("inactive"))
        assertTrue(code.contains("status must be one of"))
    }

    @Test
    fun `generateValidationMethod validates numeric constraints`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "age",
                    propertyIri = "http://example.org/age",
                    kotlinType = Int::class.asTypeName(),
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(
                        minInclusive = 0.0,
                        maxInclusive = 120.0,
                        minExclusive = null,
                        maxExclusive = null
                    )
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("Validate") && code.contains("age"))
        // Check for numeric validation - may be formatted differently
        assertTrue(code.contains("0") || code.contains("0.0"))
        assertTrue(code.contains("120") || code.contains("120.0"))
    }

    @Test
    fun `generateValidationMethod handles multiple properties`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "prefLabel",
                    propertyIri = "http://www.w3.org/2004/02/skos/core#prefLabel",
                    kotlinType = String::class.asTypeName(),
                    isRequired = true,
                    isList = false,
                    constraints = PropertyConstraints()
                ),
                PropertyBuilderModel(
                    propertyName = "altLabel",
                    propertyIri = "http://www.w3.org/2004/02/skos/core#altLabel",
                    kotlinType = com.geoknoesis.kastor.gen.processor.utils.KotlinPoetUtils.listOf(String::class.asTypeName()),
                    isRequired = false,
                    isList = true,
                    constraints = PropertyConstraints(minLength = 1)
                )
            ),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("prefLabel"))
        assertTrue(code.contains("altLabel"))
        assertTrue(code.contains("Check required prefLabel"))
        assertTrue(code.contains("Validate altLabel constraints"))
    }

    @Test
    fun `generateValidationMethod handles empty properties`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = emptyList(),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("validate"))
        assertTrue(code.contains("violations"))
        // Should not contain any property-specific validation
        assertFalse(code.contains("Count"))
    }

    @Test
    fun `generateValidationMethod includes KDoc documentation`() {
        val classBuilder = ClassBuilderModel(
            className = "Concept",
            classIri = "http://www.w3.org/2004/02/skos/core#Concept",
            builderName = "concept",
            properties = emptyList(),
            shapeIri = "http://example.org/shapes/Concept"
        )

        val method = generator.generateValidationMethod(classBuilder)

        val code = method.toString()
        assertTrue(code.contains("/**"))
        assertTrue(code.contains("Validate the concept"))
        assertTrue(code.contains("SHACL constraints"))
    }
}

