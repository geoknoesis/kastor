package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.rdf.Iri
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A class named like a type that Kotlin imports by default (`ex:String`, `ex:Pair`, `ex:List`) would shadow that type
 * in every generated file of its package: `val label: String` would mean the generated interface. Such classes are
 * generated under another name (suffix `Type`) and a warning says so. The generated sources are compiled and run.
 */
class BuiltinTypeNameTest {

    private val shacl = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <https://example.test/> .

        ex:StringShape a sh:NodeShape ; sh:targetClass ex:String ;
            sh:property [ sh:path ex:value ; sh:name "value" ; sh:datatype xsd:string ; sh:maxCount 1 ] ;
            sh:property [ sh:path ex:length ; sh:name "length" ; sh:datatype xsd:integer ; sh:maxCount 1 ] .
        ex:PairShape a sh:NodeShape ; sh:targetClass ex:Pair ;
            sh:property [ sh:path ex:label ; sh:name "label" ; sh:datatype xsd:string ; sh:maxCount 1 ; sh:minCount 1 ] ;
            sh:property [ sh:path ex:first ; sh:name "first" ; sh:class ex:String ; sh:maxCount 1 ] .
        ex:ListShape a sh:NodeShape ; sh:targetClass ex:List ;
            sh:property [ sh:path ex:items ; sh:name "items" ; sh:class ex:Pair ] ;
            sh:property [ sh:path ex:names ; sh:name "names" ; sh:datatype xsd:string ] .
        ex:PlainShape a sh:NodeShape ; sh:targetClass ex:Plain ;
            sh:property [ sh:path ex:label ; sh:name "label" ; sh:datatype xsd:string ; sh:maxCount 1 ] .
    """.trimIndent()

    private val probe = """
        package gen.builtin

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD

        fun probe(): kotlin.String {
            fun p(local: kotlin.String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val list = Iri("urn:list")
            val pair = Iri("urn:pair")
            val string = Iri("urn:string")
            g.addTriple(RdfTriple(list, p("items"), pair))
            g.addTriple(RdfTriple(list, p("names"), Literal("n")))
            g.addTriple(RdfTriple(pair, p("label"), Literal("a pair")))
            g.addTriple(RdfTriple(pair, p("first"), string))
            g.addTriple(RdfTriple(string, p("value"), Literal("text")))
            g.addTriple(RdfTriple(string, p("length"), TypedLiteral("4", XSD.integer)))
            val read: ListType = OntoMapper.materialize(RdfRef(list, g), ListType::class.java)
            val item: PairType = read.items.single()
            val first: StringType = item.first!!
            val label: kotlin.String = item.label
            return kotlin.collections.listOf(read.names, label, first.value, first.length).joinToString("|")
        }
    """.trimIndent()

    private val context = JsonLdContext(
        prefixes = emptyMap(),
        // A context term cannot choose a shadowing name either.
        typeMappings = mapOf("Any" to Iri("https://example.test/Plain")),
        propertyMappings = emptyMap(),
    )

    @Test
    fun `classes named like Kotlin built-in types are generated under another name and reported`() {
        val logger = RecordingLogger()
        val model = OntologyModel(ShaclParser(logger).parseShaclContent(shacl), context)
        val pkg = "gen.builtin"
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, pkg).values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, pkg).values
        files += DataClassGenerator(logger, "Record", NestedMode.INTERFACE, true, ValidationAnnotations.NONE).generateDataClasses(model, pkg).values
        files += DataClassFactoryGenerator(logger, "Record", NestedMode.INTERFACE, DataClassWriterGenerator(logger, "Record", NestedMode.INTERFACE))
            .generateFactories(model, pkg).values
        files += InstanceDslGenerator(logger).generate(
            InstanceDslRequest(dslName = "builtin", ontologyModel = model, packageName = "$pkg.dsl", options = DslGenerationOptions())
        )

        val names = files.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("StringType", "PairType", "ListType", "AnyType")), names.toString())
        assertTrue(names.none { it in setOf("String", "Pair", "List", "Any") }, names.toString())

        assertEquals(
            listOf(
                "class <https://example.test/List>: the name 'List' is a type that Kotlin imports by default and would shadow it in the " +
                    "generated code; the generated type is named 'ListType'",
                "class <https://example.test/Pair>: the name 'Pair' is a type that Kotlin imports by default and would shadow it in the " +
                    "generated code; the generated type is named 'PairType'",
                "class <https://example.test/Plain>: the name 'Any' is a type that Kotlin imports by default and would shadow it in the " +
                    "generated code; the generated type is named 'AnyType'",
                "class <https://example.test/String>: the name 'String' is a type that Kotlin imports by default and would shadow it in the " +
                    "generated code; the generated type is named 'StringType'",
            ),
            GenerationNames.renamedTypeWarnings(model),
        )

        val compiled = KotlinSourceCompiler.compile(files, mapOf("gen/builtin/Probe.kt" to probe))
        compiled.assertOk()
        assertEquals("[n]|a pair|text|4", compiled.classLoader().loadClass("gen.builtin.ProbeKt").getMethod("probe").invoke(null))
    }

    @Test
    fun `other names are left alone`() {
        assertEquals("Person", GenerationNames.typeIdentifier("person"))
        assertEquals("StringValue", GenerationNames.typeIdentifier("string-value"))
        assertEquals("StringType", GenerationNames.typeIdentifier("String"))
        assertEquals("UnitType", GenerationNames.typeIdentifier("unit"))
    }
}
