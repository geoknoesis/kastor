package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.*
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parse real typed SHACL bounds, then compile and execute all three generated validation paths. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypedNumericBoundsTest {
    private lateinit var loader: ClassLoader

    @BeforeAll fun compile() {
        val logger = RecordingLogger()
        val shapes = ShaclParser(logger).parseShaclContent("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <https://example.test/> .
            ex:AccountShape a sh:NodeShape; sh:targetClass ex:Account;
              sh:property [ sh:path ex:minimum; sh:datatype xsd:float; sh:maxCount 1; sh:minInclusive "16777217"^^xsd:double ];
              sh:property [ sh:path ex:maximum; sh:datatype xsd:float; sh:maxCount 1; sh:maxInclusive "16777217"^^xsd:double ];
              sh:property [ sh:path ex:above; sh:datatype xsd:float; sh:maxCount 1; sh:minExclusive "16777217"^^xsd:double ];
              sh:property [ sh:path ex:below; sh:datatype xsd:float; sh:maxCount 1; sh:maxExclusive "16777217"^^xsd:double ];
              sh:property [ sh:path ex:decimalMinimum; sh:datatype xsd:float; sh:maxCount 1; sh:minInclusive 16777217.0 ] .
            ex:ChildShape a sh:NodeShape; sh:targetClass ex:Child; sh:node ex:AccountShape;
              sh:property [ sh:path ex:minimum; sh:datatype xsd:float; sh:maxCount 1; sh:minInclusive "16777217"^^xsd:float ] .
        """.trimIndent())
        assertTrue(logger.warnings.isEmpty(), logger.warnings.toString())
        val account = shapes.single { it.targetClass.endsWith("/Account") }
        for (property in account.properties) {
            val expected = if (property.path.endsWith("/decimalMinimum")) "decimal" else "double"
            assertEquals("http://www.w3.org/2001/XMLSchema#$expected", property.numericBounds.single().datatype)
        }
        val model = OntologyModel(shapes, JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.typedbounds").values.toList() +
            OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.typedbounds").values +
            InstanceDslGenerator(logger).generate(InstanceDslRequest("accounts", model, "gen.typedbounds.dsl", DslGenerationOptions()))
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/typedbounds/Probe.kt" to """
            package gen.typedbounds
            import com.geoknoesis.kastor.gen.runtime.*
            import com.geoknoesis.kastor.rdf.*
            import com.geoknoesis.kastor.rdf.provider.MemoryGraph
            import gen.typedbounds.dsl.*

            private fun accepts(block: () -> Unit): Boolean = try { block(); true }
                catch (e: ValidationException) { false }
                catch (e: IllegalArgumentException) { false }

            fun probe(path: String, value: Float, child: Boolean): String {
                val node = Iri("urn:account")
                val setter = accepts {
                    if (child) ChildBuilder(node, MemoryGraph()).minimum(value)
                    else {
                        val builder = AccountBuilder(node, MemoryGraph())
                        when (path) {
                            "minimum" -> builder.minimum(value)
                            "maximum" -> builder.maximum(value)
                            "above" -> builder.above(value)
                            "below" -> builder.below(value)
                            "decimalMinimum" -> builder.decimalMinimum(value)
                            else -> error("Unknown path")
                        }
                    }
                }
                val graph = MemoryGraph()
                graph.addTriple(RdfTriple(node, Iri("https://example.test/" + path),
                    XsdLiterals.encode(value, Iri("http://www.w3.org/2001/XMLSchema#float"))))
                val validation = accepts {
                    if (child) ChildBuilder(node, graph).validate() else AccountBuilder(node, graph).validate()
                }
                val ref = RdfRef(node, graph)
                val report = if (child) (OntoMapper.materialize(ref, Child::class.java) as ChildWrapper).validate()
                    else (OntoMapper.materialize(ref, Account::class.java) as AccountWrapper).validate()
                return listOf(setter, validation, report !is ValidationResult.Violations).joinToString("|")
            }
        """.trimIndent()))
        result.assertOk()
        loader = result.classLoader()
    }

    private fun check(path: String, value: Float, valid: Boolean, child: Boolean = false) {
        val actual = loader.loadClass("gen.typedbounds.ProbeKt")
            .getMethod("probe", String::class.java, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(null, path, value, child)
        assertEquals("$valid|$valid|$valid", actual, "$path=$value, child=$child")
    }

    @Test fun `double bounds on float properties apply to inclusive and exclusive limits`() {
        for ((path, lowValid) in listOf("minimum" to false, "maximum" to true, "above" to false, "below" to true)) {
            check(path, 16777216f, lowValid)
            check(path, 16777218f, !lowValid)
        }
    }

    @Test fun `decimal bounds still promote to float`() {
        check("decimalMinimum", 16777216f, true)
        check("decimalMinimum", 16777214f, false)
    }

    @Test fun `inherited double bound survives an equal lexical float bound`() {
        check("minimum", 16777216f, false, child = true)
        check("minimum", 16777218f, true, child = true)
    }
}
