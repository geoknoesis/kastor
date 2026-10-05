package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessorProvider
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyProcessor
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyProcessorProvider
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the real processors under KSP (no hand-built models or mocked symbols): SHACL-driven generation by
 * `OntologyProcessor`, its interplay over several rounds with `OntoMapperProcessor` (which sees the generated `@Rdf`
 * interfaces in the next round), and prefix arrays for QName resolution. The output is compiled and executed.
 */
class KspOntologyProcessorTest {

    @TempDir
    lateinit var resources: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:name "name" ; sh:datatype xsd:string ; sh:minCount 1 ; sh:maxCount 1 ] ;
            sh:property [ sh:path ex:age ; sh:name "age" ; sh:datatype xsd:integer ; sh:maxCount 1 ; sh:minInclusive 0 ] ;
            sh:property [ sh:path ex:knows ; sh:name "knows" ; sh:class ex:Person ] .
    """.trimIndent()

    private fun options(): Map<String, String> {
        File(resources, "people.shacl.ttl").writeText(shapes)
        return mapOf(
            OntologyProcessor.RESOURCES_OPTION to resources.absolutePath,
            OntologyProcessor.RESOURCES_TRACKED_OPTION to "true",
        )
    }

    private val marker = """
        @file:Rdf(shacl = "people.shacl.ttl", validationAnnotations = ValidationAnnotations.NONE)

        package gen.people

        import com.geoknoesis.kastor.gen.annotations.Rdf
        import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
    """.trimIndent()

    /** A hand-written `@Rdf` interface that refers to a type generated in the first round. */
    private val team = """
        package gen.people

        import com.geoknoesis.kastor.gen.annotations.Rdf

        @Rdf(iri = "https://example.test/Team")
        interface Team {
            @Rdf(iri = "https://example.test/member")
            val members: List<Person>
            @Rdf(iri = "https://example.test/lead")
            val lead: Person?
        }
    """.trimIndent()

    private val probe = """
        package gen.people

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val team = Iri("urn:team")
            val ann = Iri("urn:ann")
            val bob = Iri("urn:bob")
            g.addTriple(RdfTriple(team, p("member"), ann))
            g.addTriple(RdfTriple(team, p("member"), bob))
            g.addTriple(RdfTriple(team, p("lead"), ann))
            g.addTriple(RdfTriple(ann, p("name"), Literal("Ann")))
            g.addTriple(RdfTriple(ann, p("age"), TypedLiteral("41", XSD.integer)))
            g.addTriple(RdfTriple(ann, p("knows"), bob))
            g.addTriple(RdfTriple(bob, Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), p("Person")))
            g.addTriple(RdfTriple(bob, p("age"), TypedLiteral("-1", XSD.integer)))
            val t = OntoMapper.materialize(RdfRef(team, g), Team::class.java)
            val lead = t.lead!!
            val bobResult = (g.materialize<Person>(bob) as PersonWrapper).validate()
            val components = (bobResult as ValidationResult.Violations).items
                .map { it.constraintIri.value.substringAfter('#') }.sorted()
            return listOf(
                t.members.size, lead.name, lead.age, lead.knows.size,
                (lead as PersonWrapper).validate() == ValidationResult.Ok, components,
            ).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `SHACL-driven generation and hand-written interfaces work together over several rounds`() {
        val result = KspRunner.run(
            listOf(OntologyProcessorProvider(), OntoMapperProcessorProvider()),
            mapOf("gen/people/Marker.kt" to marker, "gen/people/Team.kt" to team),
            options(),
        )
        assertEquals(emptyList(), result.errors)
        assertEquals(KotlinSymbolProcessing.ExitCode.OK, result.exitCode)
        val generated = result.generated.keys.map { it.substringAfterLast('/') }.sorted()
        // The ontology processor generated Person and its wrapper once; the @Rdf processor generated only Team's
        // wrapper, in the round after Person existed, and left the generated Person interface alone.
        assertEquals(listOf("Person.kt", "PersonWrapper.kt", "TeamWrapper.kt"), generated)
        assertFalse(result.warnings.any { "PersonWrapper" in it }, result.warnings.toString())
        assertFalse(result.warnings.any { "does not track changes" in it }, "tracked option silences: " + result.warnings)

        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("gen/people/Probe.kt" to probe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("gen.people.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("2|Ann|41|1|true|[minCount, minInclusive]", output)
    }

    @Test
    fun `a second annotation for the same shapes is skipped when identical and reported when its options differ`() {
        val same = KspRunner.run(
            OntologyProcessorProvider(),
            mapOf("gen/people/Marker.kt" to marker, "gen/people/Again.kt" to marker),
            options(),
        )
        assertEquals(emptyList(), same.errors)

        val differing = marker.replace("ValidationAnnotations.NONE", "ValidationAnnotations.NONE, generateDataClass = true")
        val result = KspRunner.run(
            OntologyProcessorProvider(),
            mapOf("gen/people/Marker.kt" to marker, "gen/people/Other.kt" to differing),
            options(),
        )
        assertTrue(
            result.errors.any { "differs from the first" in it && "generateDataClass=true" in it },
            result.errors.toString(),
        )
    }

    @Test
    fun `an ontology file that is not declared as tracked produces the staleness warning and a missing one an error`() {
        File(resources, "people.shacl.ttl").writeText(shapes)
        val untracked = KspRunner.run(
            OntologyProcessorProvider(),
            mapOf("gen/people/Marker.kt" to marker),
            mapOf(OntologyProcessor.RESOURCES_OPTION to resources.absolutePath),
        )
        assertEquals(emptyList(), untracked.errors)
        assertTrue(untracked.warnings.any { "does not track changes" in it && "people.shacl.ttl" in it }, untracked.warnings.toString())

        // A missing ontology file aborts processing (nothing is generated from a file that was not read).
        val failure = assertFailsWith<Exception> {
            KspRunner.run(
                OntologyProcessorProvider(),
                mapOf("gen/people/Marker.kt" to marker.replace("people.shacl.ttl", "absent.shacl.ttl")),
                mapOf(OntologyProcessor.RESOURCES_OPTION to resources.absolutePath),
            )
        }
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }.any { "absent.shacl.ttl" in it.message.orEmpty() },
            failure.stackTraceToString(),
        )
    }

    private val prefixed = """
        @file:Rdf(
            prefixes = [
                Prefix(name = "ex", namespace = "https://example.test/"),
                Prefix(name = "foaf", namespace = "http://xmlns.com/foaf/0.1/"),
            ],
        )

        package gen.prefixes

        import com.geoknoesis.kastor.gen.annotations.Prefix
        import com.geoknoesis.kastor.gen.annotations.Rdf

        /** Uses the file-level prefixes only. */
        @Rdf(iri = "ex:Person")
        interface Person {
            @Rdf(iri = "foaf:name")
            val name: String
            @get:Rdf(iri = "ex:nick")
            val nick: String?
        }

        /** A type-level prefix overrides the file-level one with the same name and adds a new one. */
        @Rdf(
            iri = "ex:Robot",
            prefixes = [
                Prefix(name = "foaf", namespace = "https://override.test/"),
                Prefix(name = "bot", namespace = "https://robots.test/"),
            ],
        )
        interface Robot {
            @Rdf(iri = "foaf:name")
            val name: String
            @Rdf(iri = "bot:serial")
            val serial: Long?
            @Rdf(iri = "https://example.test/full")
            val full: String?
        }
    """.trimIndent()

    private val prefixProbe = """
        package gen.prefixes

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD

        fun probe(): String {
            val g = MemoryGraph()
            val p = Iri("urn:p")
            val r = Iri("urn:r")
            g.addTriple(RdfTriple(p, Iri("http://xmlns.com/foaf/0.1/name"), Literal("Pat")))
            g.addTriple(RdfTriple(p, Iri("https://example.test/nick"), Literal("P")))
            g.addTriple(RdfTriple(r, Iri("http://xmlns.com/foaf/0.1/name"), Literal("wrong namespace")))
            g.addTriple(RdfTriple(r, Iri("https://override.test/name"), Literal("R2")))
            g.addTriple(RdfTriple(r, Iri("https://robots.test/serial"), TypedLiteral("7", XSD.long)))
            g.addTriple(RdfTriple(r, Iri("https://example.test/full"), Literal("F")))
            val person = OntoMapper.materialize(RdfRef(p, g), Person::class.java)
            val robot = OntoMapper.materialize(RdfRef(r, g), Robot::class.java)
            return listOf(person.name, person.nick, robot.name, robot.serial, robot.full).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `file-level and type-level prefix arrays resolve QNames with the type-level ones taking precedence`() {
        val result = KspRunner.run(OntoMapperProcessorProvider(), mapOf("gen/prefixes/Model.kt" to prefixed))
        assertEquals(emptyList(), result.errors)
        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("gen/prefixes/Probe.kt" to prefixProbe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("gen.prefixes.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("Pat|P|R2|7|F", output)
    }

    @Test
    fun `a QName with an undeclared prefix is an error naming the QName`() {
        val source = """
            package gen.prefixes

            import com.geoknoesis.kastor.gen.annotations.Prefix
            import com.geoknoesis.kastor.gen.annotations.Rdf

            @Rdf(iri = "ex:Thing", prefixes = [Prefix(name = "ex", namespace = "https://example.test/")])
            interface Thing {
                @Rdf(iri = "nope:label")
                val label: String
            }
        """.trimIndent()
        val result = KspRunner.run(OntoMapperProcessorProvider(), mapOf("gen/prefixes/Thing.kt" to source))
        assertTrue(result.errors.any { "nope:label" in it }, result.errors.toString())
        assertTrue(result.generated.isEmpty(), "no wrapper without the unresolved member: " + result.generated.keys)
    }
}
