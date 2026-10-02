package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclPattern
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SHACL constraints are a conjunction. When a shape restates a path that it also inherits, **every** `sh:pattern`
 * must match, **every** `sh:hasValue` must be among the values, and the `sh:nodeKind`s are intersected - none of
 * them is dropped. `sh:hasValue` is a statement about the set of values, checked when an instance is validated, not
 * about each value handed to a setter. An empty `sh:in ()` rejects every value. A DSL instance that fails leaves no
 * triple behind. The generated sources are compiled and run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShaclConjunctionTest {

    private val sh = "http://www.w3.org/ns/shacl#"

    private val shacl = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <https://example.test/> .

        ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
            sh:property [ sh:path ex:code ; sh:name "code" ; sh:datatype xsd:string ; sh:maxCount 1 ; sh:pattern "^A" ] ;
            sh:property [ sh:path ex:tag ; sh:name "tag" ; sh:datatype xsd:string ; sh:hasValue "x" ] ;
            sh:property [ sh:path ex:ref ; sh:name "ref" ; sh:nodeKind sh:IRI ] ;
            sh:property [ sh:path ex:odd ; sh:name "odd" ; sh:datatype xsd:string ; sh:nodeKind sh:Literal ] ;
            sh:property [ sh:path ex:none ; sh:name "none" ; sh:datatype xsd:string ; sh:in () ] .

        ex:ItemShape a sh:NodeShape ; sh:targetClass ex:Item ; sh:node ex:ThingShape ;
            sh:property [ sh:path ex:code ; sh:name "code" ; sh:datatype xsd:string ; sh:maxCount 1 ; sh:pattern "z$" ; sh:flags "i" ] ;
            sh:property [ sh:path ex:tag ; sh:name "tag" ; sh:datatype xsd:string ; sh:hasValue "y" ] ;
            sh:property [ sh:path ex:ref ; sh:name "ref" ; sh:nodeKind sh:BlankNodeOrIRI ] ;
            sh:property [ sh:path ex:odd ; sh:name "odd" ; sh:datatype xsd:string ; sh:nodeKind sh:IRI ] .
    """.trimIndent()

    private val probe = """
        package gen.conj

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import gen.conj.dsl.*

        private fun p(local: String) = Iri("https://example.test/" + local)

        private fun attempt(block: () -> Unit): String = try {
            block()
            "ok"
        } catch (e: ValidationException) {
            "invalid[" + listOf("x", "y").filter { ("tag must have the value: " + it) in e.message!! }.joinToString(",") + "]"
        } catch (e: IllegalArgumentException) {
            "rejected"
        }

        /** Both the own and the inherited pattern must match (the own one ignores case). */
        fun patterns(): String = listOf("AZ", "Az", "AB", "BZ").joinToString("|") { value ->
            attempt { things { item("urn:i") { code(value); tag("x", "y") } } }
        }

        /** Setters accept any value; validation requires every sh:hasValue to be among the values. */
        fun hasValues(): String = listOf(listOf("w", "x", "y"), listOf("x"), listOf("y"), emptyList()).joinToString("|") { tags ->
            attempt { things { item("urn:i") { code("AZ"); tags.forEach { tag(it) } } } }
        } + "|" + attempt { things { thing("urn:t") { tag("q"); tag("x") } } } + "|" + attempt { things { thing("urn:t") { tag("q") } } }

        fun emptyIn(): String = attempt { things { thing("urn:t") { tag("x"); none("a") } } }

        /** A failed instance leaves nothing in the graph; a later block for the same resource sees what is there. */
        fun failedInstances(): String {
            val dsl = things {
                attempt { thing("urn:bad") { tag("q") } }
                attempt { thing("urn:worse") { tag("x"); none("a") } }
                thing("urn:good") { tag("x") }
                thing("urn:good") { tag("q") }
            }
            return dsl.build().size().toString() + "|" + dsl.instances().joinToString(",") { (it as Iri).value } + "|" +
                dsl.build().getTriples().map { (it.subject as Iri).value }.distinct().joinToString(",")
        }

        fun wrapper(): String {
            fun check(configure: (MemoryGraph, Iri) -> Unit): String {
                val g = MemoryGraph()
                val node = Iri("urn:n")
                configure(g, node)
                val result = (OntoMapper.materialize(RdfRef(node, g), Item::class.java) as ItemWrapper).validate()
                return if (result is ValidationResult.Violations) {
                    result.items.map { it.constraintIri.value.substringAfter('#') + ":" + it.path!!.value.substringAfterLast('/') }.sorted().joinToString(",")
                } else {
                    "ok"
                }
            }
            val good = check { g, n ->
                g.addTriple(RdfTriple(n, p("code"), Literal("Az")))
                g.addTriple(RdfTriple(n, p("ref"), Iri("urn:r")))
            }
            val bad = check { g, n ->
                g.addTriple(RdfTriple(n, p("code"), Literal("BZ")))
                g.addTriple(RdfTriple(n, p("ref"), BlankNode("b1")))
                g.addTriple(RdfTriple(n, p("odd"), Literal("v")))
                g.addTriple(RdfTriple(n, p("odd"), Iri("urn:o")))
                g.addTriple(RdfTriple(n, p("none"), Literal("a")))
            }
            return good + "|" + bad
        }
    """.trimIndent()

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private val logger = RecordingLogger()
    private lateinit var model: OntologyModel
    private lateinit var result: KotlinSourceCompiler.Result

    @BeforeAll
    fun compile() {
        model = OntologyModel(ShaclParser(logger).parseShaclContent(shacl), emptyContext)
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.conj").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.conj").values
        files += InstanceDslGenerator(logger).generate(
            InstanceDslRequest(dslName = "things", ontologyModel = model, packageName = "gen.conj.dsl", options = DslGenerationOptions())
        )
        result = KotlinSourceCompiler.compile(files, mapOf("gen/conj/Probe.kt" to probe))
        result.assertOk()
    }

    private val loader by lazy { result.classLoader() }
    private fun call(function: String): Any? = loader.loadClass("gen.conj.ProbeKt").getMethod(function).invoke(null)

    private fun constraints(cls: String, path: String) =
        GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model)).getValue(EX + cls).first { it.path == EX + path }.constraints

    @Test
    fun `an empty sh in is kept by the parser as a list without members`() {
        val none = model.shapes.first { it.targetClass == EX + "Thing" }.properties.first { it.path == EX + "none" }
        assertEquals(emptyList(), none.inValues, "sh:in () is not 'no sh:in'")
        assertEquals(emptyList(), none.inValuesTyped)
    }

    @Test
    fun `own and inherited patterns, hasValues and nodeKinds are all kept`() {
        val code = constraints("Item", "code")
        assertEquals(listOf(ShaclPattern("z$", "i"), ShaclPattern("^A", null)), code.patterns)
        assertEquals(listOf("y", "x"), constraints("Item", "tag").hasValues)
        assertEquals("${sh}IRI", constraints("Item", "ref").nodeKind, "sh:BlankNodeOrIRI and sh:IRI: only an IRI satisfies both")
        assertFalse(constraints("Item", "ref").nodeKindUnsatisfiable)
        assertTrue(constraints("Item", "odd").nodeKindUnsatisfiable, "sh:IRI and sh:Literal have no kind in common")
        assertFalse(constraints("Thing", "odd").nodeKindUnsatisfiable)
        assertEquals(listOf("x"), constraints("Thing", "tag").hasValues)

        val warnings = ArrayList<String>()
        GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model)) { warnings += it }
        assertTrue(
            warnings.any { "ItemShape" in it && "<${EX}odd>" in it && "sh:nodeKind" in it && "every value" in it },
            warnings.toString(),
        )
        assertTrue(warnings.any { "ThingShape" in it && "<${EX}none>" in it && "sh:in" in it }, warnings.toString())
    }

    @Test
    fun `DSL setters check every pattern that applies`() {
        assertEquals("ok|ok|rejected|rejected", call("patterns"))
    }

    @Test
    fun `sh hasValue is checked over the whole value set at validation, not per setter call`() {
        assertEquals("ok|invalid[y]|invalid[x]|invalid[x,y]|ok|invalid[x]", call("hasValues"))
    }

    @Test
    fun `an empty sh in rejects every value`() {
        assertEquals("rejected", call("emptyIn"))
    }

    @Test
    fun `a DSL instance that fails leaves no triple in the graph`() {
        // urn:good: rdf:type, tag "x" and tag "q" (the second block validates against what the first one wrote).
        assertEquals("3|urn:good,urn:good|urn:good", call("failedInstances"))
    }

    @Test
    fun `embedded validation checks every pattern, the intersected node kind and the empty sh in`() {
        assertEquals("ok|datatype:odd,in:none,nodeKind:odd,nodeKind:odd,nodeKind:ref,pattern:code", call("wrapper"))
    }
}
