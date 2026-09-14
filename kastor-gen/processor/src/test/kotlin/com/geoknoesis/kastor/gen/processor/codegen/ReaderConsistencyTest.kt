package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every generated reader follows the same rules:
 * - a value missing for a required member (`sh:minCount >= 1`, single or list) throws `MaterializationException`
 *   naming the shape and path, in live wrappers and in data-class factories alike;
 * - a value that cannot be decoded follows `MaterializationPolicy.illTypedValues`, also for wrappers of
 *   hand-written `@Rdf` interfaces.
 */
class ReaderConsistencyTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private val shaclProbe = """
        package gen.consistency

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun outcome(block: () -> Any?): String = try {
            block()
            "ok"
        } catch (e: MaterializationException) {
            val m = e.message.orEmpty()
            "throws(" + (m.contains("https://example.test/DocShape") && m.contains("https://example.test/")) + ")"
        }

        fun probe(): String {
            val g = MemoryGraph()
            val doc = Iri("urn:doc")
            g.addTriple(RdfTriple(doc, Iri("https://example.test/title"), Literal("T")))
            val wrapper = OntoMapper.materialize(RdfRef(doc, g), Doc::class.java)
            return listOf(
                "tags=" + outcome { wrapper.tags },
                "refs=" + outcome { wrapper.refs },
                "title=" + outcome { wrapper.title },
                "record=" + outcome { OntoMapper.materialize(RdfRef(doc, g), DocRecord::class.java) },
                "missingTitle=" + outcome { OntoMapper.materialize(RdfRef(Iri("urn:none"), g), Doc::class.java).title },
            ).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `empty required lists throw in live wrappers and data class factories`() {
        val logger = RecordingLogger()
        val model = OntologyModel(
            listOf(
                ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("name"))),
                ShaclShape(
                    EX + "DocShape", EX + "Doc",
                    listOf(
                        prop("tags", minCount = 1, maxCount = null),
                        prop("refs", targetClass = EX + "Person", minCount = 1, maxCount = null),
                        prop("title", minCount = 1),
                    ),
                ),
            ),
            emptyContext,
        )
        val pkg = "gen.consistency"
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, pkg).values +
            OntologyWrapperGenerator(logger).generateWrappers(model, pkg).values +
            DataClassGenerator(logger, "Record", NestedMode.INTERFACE, false, ValidationAnnotations.NONE).generateDataClasses(model, pkg).values +
            DataClassFactoryGenerator(logger, "Record", NestedMode.INTERFACE).generateFactories(model, pkg).values
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/consistency/Probe.kt" to shaclProbe))
        result.assertOk()
        assertEquals(
            "tags=throws(true)|refs=throws(true)|title=ok|record=throws(true)|missingTitle=throws(true)",
            result.classLoader().loadClass("gen.consistency.ProbeKt").getMethod("probe").invoke(null),
        )
    }

    private val kspInterface = """
        package gen.kspdecode

        interface Measure {
            val counts: List<Int>
            val maybe: Int?
            val must: Int
        }
    """.trimIndent()

    private val kspProbe = """
        package gen.kspdecode

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun outcome(block: () -> Any?): String = try {
            "" + block()
        } catch (e: MaterializationException) {
            "throws"
        }

        fun probe(): String {
            val g = MemoryGraph()
            val n = Iri("urn:m")
            g.addTriple(RdfTriple(n, Iri("https://example.test/counts"), Literal("1")))
            g.addTriple(RdfTriple(n, Iri("https://example.test/counts"), Literal("x")))
            g.addTriple(RdfTriple(n, Iri("https://example.test/maybe"), Literal("y")))
            g.addTriple(RdfTriple(n, Iri("https://example.test/must"), Literal("z")))
            fun read(): String {
                val m = OntoMapper.materialize(RdfRef(n, g), Measure::class.java)
                return outcome { m.counts } + "," + outcome { m.maybe } + "," + outcome { m.must }
            }
            val strict = read()
            val previous = MaterializationPolicy.illTypedValues
            MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP
            try {
                return strict + "|" + read()
            } finally {
                MaterializationPolicy.illTypedValues = previous
            }
        }
    """.trimIndent()

    @Test
    fun `hand written interface wrappers apply the ill typed value policy`() {
        val model = ClassModel(
            qualifiedName = "gen.kspdecode.Measure",
            simpleName = "Measure",
            packageName = "gen.kspdecode",
            classIri = "https://example.test/Measure",
            properties = listOf(
                PropertyModel("counts", "List<Int>", "https://example.test/counts", PropertyType.LITERAL),
                PropertyModel("maybe", "Int", "https://example.test/maybe", PropertyType.LITERAL, nullable = true),
                PropertyModel("must", "Int", "https://example.test/must", PropertyType.LITERAL),
            ),
        )
        val file = WrapperGenerator(RecordingLogger()).generateWrapper(model)
        val result = KotlinSourceCompiler.compile(listOf(file), mapOf("gen/kspdecode/Measure.kt" to kspInterface, "gen/kspdecode/Probe.kt" to kspProbe))
        result.assertOk()
        // SKIP: ill-typed values are left out; a required value that is left out is then missing.
        assertEquals(
            "throws,throws,throws|[1],null,throws",
            result.classLoader().loadClass("gen.kspdecode.ProbeKt").getMethod("probe").invoke(null),
        )
    }
}
