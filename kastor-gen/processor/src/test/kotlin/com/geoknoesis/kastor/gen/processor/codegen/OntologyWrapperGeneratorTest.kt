package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.rdf.Iri
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OntologyWrapperGeneratorTest {

    private lateinit var logger: KSPLogger
    private lateinit var generator: OntologyWrapperGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        generator = OntologyWrapperGenerator(logger)
    }

    @Test
    fun `generateWrappers creates catalog wrapper`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://purl.org/dc/terms/title",
                    name = "title",
                    description = "A name given to the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://purl.org/dc/terms/description",
                    name = "description",
                    description = "A free-text account of the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://www.w3.org/ns/dcat#dataset",
                    name = "dataset",
                    description = "A collection of data that is listed in the catalog.",
                    datatype = null,
                    targetClass = "http://www.w3.org/ns/dcat#Dataset",
                    minCount = 0,
                    maxCount = null
                )
            )
        )

        val context = JsonLdContext(
            prefixes = mapOf(
                "dcat" to "http://www.w3.org/ns/dcat#",
                "dcterms" to "http://purl.org/dc/terms/"
            ),
            typeMappings = mapOf(
                "Catalog" to Iri("http://www.w3.org/ns/dcat#Catalog"),
                "Dataset" to Iri("http://www.w3.org/ns/dcat#Dataset")
            ),
            propertyMappings = mapOf(
                "title" to JsonLdProperty(
                    id = Iri("http://purl.org/dc/terms/title"),
                    type = JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#string"))
                ),
                "description" to JsonLdProperty(
                    id = Iri("http://purl.org/dc/terms/description"),
                    type = JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#string"))
                ),
                "dataset" to JsonLdProperty(
                    id = Iri("http://www.w3.org/ns/dcat#dataset"),
                    type = JsonLdType.Id
                )
            )
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(1, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))

        val catalogCode = java.io.StringWriter().also { wrappers["CatalogWrapper"]!!.writeTo(it) }.toString()
        
        // Check package declaration
        assertTrue(catalogCode.contains("package com.example.test"))
        
        // Check imports
        assertTrue(catalogCode.contains("import com.geoknoesis.kastor.gen.runtime"))
        assertTrue(catalogCode.contains("import com.geoknoesis.kastor.rdf"))
        
        // Check class declaration
        assertTrue(catalogCode.contains("internal class CatalogWrapper"))
        assertTrue(catalogCode.contains("override val rdf: RdfHandle"))
        assertTrue(catalogCode.contains("Catalog") && catalogCode.contains("RdfBacked"))
        
        // Check known predicates
        assertTrue(catalogCode.contains("private val known: Set<Iri>"))
        assertTrue(catalogCode.contains("setOf"))
        assertTrue(catalogCode.contains("Iri(\"http://purl.org/dc/terms/title\")"))
        assertTrue(catalogCode.contains("Iri(\"http://purl.org/dc/terms/description\")"))
        assertTrue(catalogCode.contains("Iri(\"http://www.w3.org/ns/dcat#dataset\")"))
        
        // Check property implementations
        assertTrue(catalogCode.contains("override val title: String by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/title\"))"))
        assertTrue(catalogCode.contains(".lexical"))
        
        assertTrue(catalogCode.contains("override val description: String? by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/description\"))"))
        assertTrue(catalogCode.contains(".map { it.lexical }.firstOrNull()"))
        
        assertTrue(catalogCode.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://www.w3.org/ns/dcat#dataset\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java)"))
        
        // Check companion object
        assertTrue(catalogCode.contains("companion object {"))
        assertTrue(catalogCode.contains("OntoMapper.registry[Catalog::class.java]"))
        assertTrue(catalogCode.contains("CatalogWrapper(handle)"))
    }

    @Test
    fun `generateWrappers handles different datatypes`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/stringProp",
                    name = "stringProp",
                    description = "String property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/intProp",
                    name = "intProp",
                    description = "Integer property",
                    datatype = "http://www.w3.org/2001/XMLSchema#int",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/booleanProp",
                    name = "booleanProp",
                    description = "Boolean property",
                    datatype = "http://www.w3.org/2001/XMLSchema#boolean",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/doubleProp",
                    name = "doubleProp",
                    description = "Double property",
                    datatype = "http://www.w3.org/2001/XMLSchema#double",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = java.io.StringWriter().also { wrappers["TestWrapper"]!!.writeTo(it) }.toString()
        
        // Check string property
        assertTrue(testCode.contains("override val stringProp: String by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/stringProp\"))"))
        assertTrue(testCode.contains(".lexical"))
        
        // Check int property
        assertTrue(testCode.contains("override val intProp: Int by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/intProp\"))"))
        assertTrue(testCode.contains(".lexical.toInt()"))
        
        // Check boolean property
        assertTrue(testCode.contains("override val booleanProp: Boolean by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/booleanProp\"))"))
        assertTrue(testCode.contains(".lexical.toBooleanStrict()"))
        
        // Check double property
        assertTrue(testCode.contains("override val doubleProp: Double by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/doubleProp\"))"))
        assertTrue(testCode.contains(".lexical.toDouble()"))
    }

    @Test
    fun `generateWrappers handles cardinality constraints`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/singleProp",
                    name = "singleProp",
                    description = "Single value property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/multipleProp",
                    name = "multipleProp",
                    description = "Multiple value property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 5
                ),
                ShaclProperty(
                    path = "http://example.org/unboundedProp",
                    name = "unboundedProp",
                    description = "Unbounded property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = null
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = java.io.StringWriter().also { wrappers["TestWrapper"]!!.writeTo(it) }.toString()
        
        // Single value property
        assertTrue(testCode.contains("override val singleProp: String by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/singleProp\"))"))
        assertTrue(testCode.contains(".lexical"))
        
        // Multiple value property
        assertTrue(testCode.contains("override val multipleProp: List<String> by lazy {"))
        assertTrue(testCode.contains(".map { it.lexical }"))
        
        // Unbounded property
        assertTrue(testCode.contains("override val unboundedProp: List<String> by lazy {"))
        assertTrue(testCode.contains(".map { it.lexical }"))
    }

    @Test
    fun `generateWrappers handles object properties`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://www.w3.org/ns/dcat#dataset",
                    name = "dataset",
                    description = "Dataset property",
                    datatype = null,
                    targetClass = "http://www.w3.org/ns/dcat#Dataset",
                    minCount = 0,
                    maxCount = null
                ),
                ShaclProperty(
                    path = "http://purl.org/dc/terms/publisher",
                    name = "publisher",
                    description = "Publisher property",
                    datatype = null,
                    targetClass = "http://xmlns.com/foaf/0.1/Agent",
                    minCount = 0,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val catalogCode = java.io.StringWriter().also { wrappers["CatalogWrapper"]!!.writeTo(it) }.toString()
        
        // List object property
        assertTrue(catalogCode.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://www.w3.org/ns/dcat#dataset\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java)"))
        
        // Single object property
        assertTrue(catalogCode.contains("override val publisher: Agent? by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/publisher\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Agent::class.java)"))
        assertTrue(catalogCode.contains(".firstOrNull()"))
    }

    @Test
    fun `generateWrappers handles multiple shapes`() {
        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Catalog",
                targetClass = "http://www.w3.org/ns/dcat#Catalog",
                properties = listOf(
                    ShaclProperty(
                        path = "http://purl.org/dc/terms/title",
                        name = "title",
                        description = "Title",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/Dataset",
                targetClass = "http://www.w3.org/ns/dcat#Dataset",
                properties = listOf(
                    ShaclProperty(
                        path = "http://purl.org/dc/terms/title",
                        name = "title",
                        description = "Title",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(2, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))
        assertTrue(wrappers.containsKey("DatasetWrapper"))

        val catalogCode = java.io.StringWriter().also { wrappers["CatalogWrapper"]!!.writeTo(it) }.toString()
        val datasetCode = java.io.StringWriter().also { wrappers["DatasetWrapper"]!!.writeTo(it) }.toString()

        assertTrue(catalogCode.contains("internal class CatalogWrapper"))
        assertTrue(datasetCode.contains("internal class DatasetWrapper"))
    }

    @Test
    fun `generateWrappers handles empty properties`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Empty",
            targetClass = "http://example.org/Empty",
            properties = emptyList()
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val emptyCode = java.io.StringWriter().also { wrappers["EmptyWrapper"]!!.writeTo(it) }.toString()
        
        assertTrue(emptyCode.contains("internal class EmptyWrapper"))
        assertTrue(emptyCode.contains("override val rdf: RdfHandle"))
        assertTrue(emptyCode.contains("Empty") && emptyCode.contains("RdfBacked"))
        assertTrue(emptyCode.contains("private val known: Set<Iri>"))
        assertTrue(emptyCode.contains("setOf"))
        assertTrue(emptyCode.contains(")"))
        assertTrue(emptyCode.contains("companion object {"))
        assertTrue(emptyCode.contains("OntoMapper.registry[Empty::class.java]"))
        assertTrue(emptyCode.contains("EmptyWrapper(handle)"))
    }

    @Test
    fun `generateWrappers includes proper documentation`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://purl.org/dc/terms/title",
                    name = "title",
                    description = "A name given to the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val catalogCode = java.io.StringWriter().also { wrappers["CatalogWrapper"]!!.writeTo(it) }.toString()
        
        // Check class documentation
        assertTrue(catalogCode.contains("RDF-backed wrapper for Catalog"))
        assertTrue(catalogCode.contains("Generated from SHACL shape: http://example.org/shapes/Catalog"))
        
        // Check property documentation
        assertTrue(catalogCode.contains("A name given to the catalog."))
        assertTrue(catalogCode.contains("Path: http://purl.org/dc/terms/title"))
    }

    @Test
    fun `generateWrappers handles unknown datatypes`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/unknownProp",
                    name = "unknownProp",
                    description = "Unknown type property",
                    datatype = "http://example.org/unknownType",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = java.io.StringWriter().also { wrappers["TestWrapper"]!!.writeTo(it) }.toString().toString()
        
        // Unknown datatypes should default to String
        assertTrue(testCode.contains("override val unknownProp: String by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/unknownProp\"))"))
        assertTrue(testCode.contains(".lexical"))
    }

    @Test
    fun `generateWrappers handles interface name extraction`() {
        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Catalog",
                targetClass = "http://www.w3.org/ns/dcat#Catalog",
                properties = emptyList()
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/Dataset",
                targetClass = "http://www.w3.org/ns/dcat#Dataset",
                properties = emptyList()
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/DataDistribution",
                targetClass = "http://www.w3.org/ns/dcat#DataDistribution",
                properties = emptyList()
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(3, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))
        assertTrue(wrappers.containsKey("DatasetWrapper"))
        assertTrue(wrappers.containsKey("DataDistributionWrapper"))
    }

    @Test
    fun `embedded validate enforces sh pattern`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Doc",
            targetClass = "http://example.org/Doc",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/documentId",
                    name = "documentId",
                    description = "Document identifier",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1,
                    pattern = "DOC-[0-9]+"
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val wrappers = generator.generateWrappers(OntologyModel(listOf(shape), context), "com.example.test")
        val code = java.io.StringWriter().also { wrappers["DocWrapper"]!!.writeTo(it) }.toString()

        // validate() must enforce sh:pattern, not only cardinality
        assertTrue(
            code.contains("constraintIri = SHACL.pattern"),
            "Generated validate() should emit a sh:pattern violation check"
        )
        assertTrue(
            code.contains("DOC-[0-9]+"),
            "Generated validate() should reference the declared pattern"
        )
        // SHACL sh:pattern uses SPARQL REGEX (unanchored find) semantics, not full-string match.
        assertTrue(
            code.contains(".containsMatchIn("),
            "sh:pattern must use containsMatchIn (find), per SHACL/SPARQL REGEX semantics"
        )
        assertFalse(
            code.contains(".matches("),
            "sh:pattern must not use anchored .matches() (full-string)"
        )
    }

    @Test
    fun `embedded validate enforces string length bounds`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Doc",
            targetClass = "http://example.org/Doc",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/title",
                    name = "title",
                    description = "Title",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1,
                    minLength = 2,
                    maxLength = 20
                )
            )
        )
        val context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val wrappers = generator.generateWrappers(OntologyModel(listOf(shape), context), "com.example.test")
        val code = java.io.StringWriter().also { wrappers["DocWrapper"]!!.writeTo(it) }.toString()

        assertTrue(code.contains("constraintIri = SHACL.minLength"), "validate() should enforce sh:minLength")
        assertTrue(code.contains("constraintIri = SHACL.maxLength"), "validate() should enforce sh:maxLength")
    }

    @Test
    fun `embedded validate enforces numeric bounds`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Doc",
            targetClass = "http://example.org/Doc",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/score",
                    name = "score",
                    description = "Score",
                    datatype = "http://www.w3.org/2001/XMLSchema#decimal",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1,
                    minInclusive = 0.0,
                    maxExclusive = 100.0
                )
            )
        )
        val context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val wrappers = generator.generateWrappers(OntologyModel(listOf(shape), context), "com.example.test")
        val code = java.io.StringWriter().also { wrappers["DocWrapper"]!!.writeTo(it) }.toString()

        assertTrue(code.contains("constraintIri = SHACL.minInclusive"), "validate() should enforce sh:minInclusive")
        assertTrue(code.contains("constraintIri = SHACL.maxExclusive"), "validate() should enforce sh:maxExclusive")
    }

    @Test
    fun `embedded validate enforces sh in membership`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Doc",
            targetClass = "http://example.org/Doc",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/status",
                    name = "status",
                    description = "Status",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1,
                    inValues = listOf("DRAFT", "ACTIVE", "ARCHIVED")
                )
            )
        )
        val context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val wrappers = generator.generateWrappers(OntologyModel(listOf(shape), context), "com.example.test")
        val code = java.io.StringWriter().also { wrappers["DocWrapper"]!!.writeTo(it) }.toString()

        assertTrue(code.contains("constraintIri = SHACL.`in`"), "validate() should enforce sh:in")
        assertTrue(code.contains("DRAFT") && code.contains("ACTIVE"), "validate() should reference the allowed values")
    }

    @Test
    fun `wrapper reads an IRI enum via from`() {
        val prop = com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus",
        )
        val shape = ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))
        val enum = com.geoknoesis.kastor.gen.processor.api.model.EnumModel(
            "DocumentStatus", "https://ex/#DocumentStatus",
            com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind.IRI,
            listOf(com.geoknoesis.kastor.gen.processor.api.model.EnumMember("DRAFT", iri = "https://ex/#DRAFT")))
        val ctx = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val model = OntologyModel(listOf(shape), ctx, enums = listOf(enum))
        val code = java.io.StringWriter().also {
            generator.generateWrappers(model, "com.example").getValue("DocWrapper").writeTo(it) }.toString()
        assertTrue(code.contains("override val status: DocumentStatus?"))
        assertTrue(code.contains("DocumentStatus.from("))
        assertFalse(code.contains("OntoMapper.materialize"), "enum property must not route through the object/materialize path")
    }

    @Test
    fun `sh in literal values are escaped in generated validate`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Doc",
            targetClass = "http://example.org/Doc",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/code",
                    name = "code",
                    description = "Code",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1,
                    inValues = listOf("A\"B")
                )
            )
        )
        val context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val code = java.io.StringWriter().also {
            generator.generateWrappers(OntologyModel(listOf(shape), context), "com.example").getValue("DocWrapper").writeTo(it)
        }.toString()
        // The sh:in value contains a double-quote; it must be emitted escaped so the generated listOf(...) compiles.
        assertTrue(
            code.contains("\"A\\\"B\""),
            "sh:in value with a quote must be KotlinPoet-escaped in the generated validate()"
        )
    }
}














