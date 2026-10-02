package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `sh:pattern` uses XPath regular expressions. Generated code must translate the common XPath-only syntax
 * (`\i`, `\c`, character-class subtraction), reject patterns that are still invalid at generation time, and never
 * compile a pattern during class initialisation or on every setter call.
 */
class PatternGenerationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private val backslash = "\\"

    private fun model(vararg extra: com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty) = OntologyModel(
        listOf(
            ShaclShape(
                EX + "CodeShape", EX + "Code",
                listOf(
                    prop("code").copy(pattern = "^${backslash}i${backslash}c*$"),
                    prop("consonants").copy(pattern = "^[a-z-[aeiou]]+$"),
                ) + extra,
            )
        ),
        emptyContext,
    )

    private val probe = """
        package gen.pattern

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val ok = Iri("urn:ok")
            g.addTriple(RdfTriple(ok, p("code"), Literal("abc")))
            g.addTriple(RdfTriple(ok, p("consonants"), Literal("bcd")))
            val bad = Iri("urn:bad")
            g.addTriple(RdfTriple(bad, p("code"), Literal("1abc")))
            g.addTriple(RdfTriple(bad, p("consonants"), Literal("abc")))
            fun count(node: Iri): Int {
                val code = OntoMapper.materialize(RdfRef(node, g), Code::class.java) as CodeWrapper
                val r = code.validate()
                return if (r is ValidationResult.Violations) r.items.size else 0
            }
            return "" + count(ok) + "|" + count(bad)
        }
    """.trimIndent()

    @Test
    fun `XPath pattern syntax is translated and validated at runtime`() {
        val logger = RecordingLogger()
        val model = model()
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.pattern").values +
            OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.pattern").values +
            InstanceDslGenerator(logger).generate(InstanceDslRequest("codes", model, "gen.pattern.dsl", DslGenerationOptions()))
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/pattern/Probe.kt" to probe))
        result.assertOk()
        assertEquals("0|2", result.classLoader().loadClass("gen.pattern.ProbeKt").getMethod("probe").invoke(null))
    }

    @Test
    fun `pattern objects are lazy and compiled once`() {
        val logger = RecordingLogger()
        val model = model()
        val wrapper = OntologyWrapperGenerator(logger).generateWrappers(model, "gen.pattern").values.single().toString()
        assertEquals(2, Regex("by\\s+lazy\\s*\\{\\s*Regex\\(").findAll(wrapper).count(), wrapper)
        assertEquals(2, Regex("Regex\\(").findAll(wrapper).count(), wrapper)
        val dsl = InstanceDslGenerator(logger).generate(InstanceDslRequest("codes", model, "gen.pattern.dsl", DslGenerationOptions())).toString()
        assertEquals(2, Regex("Regex\\(").findAll(dsl).count(), dsl)
        assertEquals(2, Regex("by\\s+lazy\\s*\\{\\s*Regex\\(").findAll(dsl).count(), dsl)
    }

    @Test
    fun `an invalid pattern fails generation naming the shape and path`() {
        val model = model(prop("broken").copy(pattern = "(unclosed"))
        val e = assertFailsWith<InvalidConfigurationException> {
            OntologyWrapperGenerator(RecordingLogger()).generateWrappers(model, "gen.pattern")
        }
        assertTrue(e.message!!.contains("${EX}CodeShape") && e.message!!.contains("${EX}broken"), e.message)
        assertFailsWith<InvalidConfigurationException> {
            InstanceDslGenerator(RecordingLogger()).generate(InstanceDslRequest("codes", model, "gen.pattern.dsl", DslGenerationOptions()))
        }
    }

    /**
     * The rules shared with the Kastor SHACL validator (the tables in `utils/parity` pin the translation itself):
     * `java.util.regex` syntax that would not mean the same is rejected when the code is generated, with a message
     * that names the shape, the path and the construct.
     */
    @Test
    fun `patterns that cannot be translated faithfully fail generation naming the shape the path and the construct`() {
        val rejected = listOf(
            // pattern, flags, what the message says
            Triple("[a-c[x-z]]", null, "unescaped '['"),
            Triple("[a-z&&[^aeiou]]", null, "'&&'"),
            Triple("[]a]", null, "']'"),
            Triple("[a--b]", null, "unescaped '-'"),
            Triple("a(?i)b", null, "inline flag group \"(?i)\""),
            Triple("(?i:abc)", null, "inline flag group \"(?i:\""),
            Triple("(?u)abc", null, "inline flag group \"(?u)\""),
            Triple("${backslash}p{IsAlphabetic}", null, "neither a Unicode block nor a Unicode script"),
            Triple("[[:word:]]", null, "POSIX"),
            Triple("(?x)a # comment", null, "'#'"),
            Triple("abc", "z", "Unsupported sh:flags character 'z'"),
        )
        for ((pattern, flags, expected) in rejected) {
            val model = model(prop("broken").copy(pattern = pattern, patternFlags = flags))
            for (generate in listOf<() -> Unit>(
                { OntologyWrapperGenerator(RecordingLogger()).generateWrappers(model, "gen.pattern") },
                { InstanceDslGenerator(RecordingLogger()).generate(InstanceDslRequest("codes", model, "gen.pattern.dsl", DslGenerationOptions())) },
            )) {
                val e = assertFailsWith<InvalidConfigurationException>(pattern) { generate() }
                val message = e.message!!
                assertTrue("${EX}CodeShape" in message && "${EX}broken" in message, "$pattern: $message")
                assertTrue(expected in message, "$pattern: expected \"$expected\" in: $message")
            }
        }
    }

    @Test
    fun `leading inline flags POSIX classes and script names are generated with their meaning`() {
        val model = OntologyModel(
            listOf(
                ShaclShape(
                    EX + "CodeShape", EX + "Code",
                    listOf(
                        prop("caseless").copy(pattern = "(?i)^abc$"),
                        prop("posix").copy(pattern = "^[[:alpha:]_]+$"),
                        prop("script").copy(pattern = "^${backslash}p{IsLatin}+$"),
                        prop("upper").copy(pattern = "^x${backslash}p{Lu}$", patternFlags = "i"),
                    ),
                )
            ),
            emptyContext,
        )
        val dsl = InstanceDslGenerator(RecordingLogger()).generate(InstanceDslRequest("codes", model, "gen.flags.dsl", DslGenerationOptions()))
        assertTrue("RegexOption.IGNORE_CASE" in dsl.toString(), "the leading (?i) became an option of the generated Regex")
        assertFalse("Regex(\"(?i)" in dsl.toString(), "and is no longer part of the pattern that is compiled")
        assertTrue("Regex(\"^abc" in dsl.toString(), dsl.toString())
        val probe = """
            package gen.flags.dsl

            import com.geoknoesis.kastor.rdf.Iri
            import com.geoknoesis.kastor.rdf.provider.MemoryGraph

            private fun ok(block: CodeBuilder.() -> Unit): String =
                try { CodeBuilder(Iri("urn:c"), MemoryGraph()).block(); "ok" } catch (e: IllegalArgumentException) { "no" }

            fun probe(): String = listOf(
                ok { caseless("ABC") }, ok { caseless("abd") },
                ok { posix("ab_C") }, ok { posix("a1") },
                ok { script("abc") }, ok { script("a1") },
                // XPath: the i flag does not make a category escape case-insensitive.
                ok { upper("XA") }, ok { upper("xa") },
            ).joinToString("|")
        """.trimIndent()
        val result = KotlinSourceCompiler.compile(listOf(dsl), mapOf("gen/flags/dsl/Probe.kt" to probe))
        result.assertOk()
        assertEquals("ok|no|ok|no|ok|no|ok|no", result.classLoader().loadClass("gen.flags.dsl.ProbeKt").getMethod("probe").invoke(null))
    }
}
