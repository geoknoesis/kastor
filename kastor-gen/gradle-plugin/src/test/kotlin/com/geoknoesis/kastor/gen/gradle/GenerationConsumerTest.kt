package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.SparqlSelect
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A real consumer build: applies the Kotlin and Kastor Gen plugins, generates, compiles and runs. It resolves nothing
 * from the network (see [ConsumerBuild]).
 */
class GenerationConsumerTest {
    @TempDir lateinit var dir: File

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `consumer compiles renamed cross package types and regenerates changed inputs`() {
        File(dir, "settings.gradle").writeText(ConsumerBuild.settings("consumer"))
        File(dir, "gradle.properties").writeText(ConsumerBuild.properties)
        val classpath = listOf(Rdf::class.java, RdfHandle::class.java, SparqlSelect::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).invariantSeparatorsPath }.distinct()
            .joinToString(",") { "\"$it\"" }
        File(dir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm")
                id("com.geoknoesis.kastor.gen")
                application
            }
            kotlin { jvmToolchain(21) }
            dependencies { implementation(files($classpath)) }
            application { mainClass.set("MainKt") }
            kastorGen {
                ontologies {
                    create("sample") {
                        shaclPath = "shapes.ttl"
                        contextPath = "context.json"
                        interfacePackage = "demo.domain"
                        wrapperPackage = "demo.impl"
                    }
                }
            }
        """.trimIndent())
        File(dir, "shapes.ttl").writeText("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://example.test/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:PersonShape a sh:NodeShape; sh:targetClass ex:Person;
                sh:property [ sh:path ex:title; sh:name "Title"; sh:datatype xsd:string; sh:maxCount 1;
                              sh:description "100% of image/* files */ and ${'$'}graph" ];
                sh:property [ sh:path ex:issued; sh:name "date-issued"; sh:datatype xsd:date; sh:maxCount 1 ];
                sh:property [ sh:path ex:clazz; sh:name "class"; sh:datatype xsd:string; sh:maxCount 1 ];
                sh:property [ sh:path ex:friend; sh:name "friend"; sh:class ex:Person; sh:maxCount 1 ];
                sh:property [ sh:path ex:external; sh:name "external"; sh:class ex:External; sh:maxCount 1 ] .
            ex:OldShape a sh:NodeShape; sh:targetClass ex:Old .
        """.trimIndent())
        File(dir, "context.json").writeText("""{"@context":{"ex":"https://example.test/","Human":"ex:Person","Old":"ex:Old"}}""")
        File(dir, "src/main/kotlin").mkdirs()
        File(dir, "src/main/kotlin/Main.kt").writeText("""
            import com.geoknoesis.kastor.rdf.*
            import com.geoknoesis.kastor.gen.runtime.*
            import demo.domain.Human
            fun main() {
                val graph = com.geoknoesis.kastor.rdf.provider.MemoryGraph()
                val node = Iri("urn:person")
                graph.addTriple(RdfTriple(node, Iri("https://example.test/friend"), node))
                val human = OntoMapper.materialize(RdfRef(node, graph), Human::class.java)
                check(human.friend != null)
                check(human.external == null)
                check(human.title == null && human.dateIssued == null && human.`class` == null)
                println("consumer-ok")
            }
        """.trimIndent())
        fun run(vararg args: String) = ConsumerBuild.build(dir, *args)
        assertTrue(run("run").output.contains("consumer-ok"))
        val cached = run("run")
        assertTrue(cached.output.contains("Reusing configuration cache"), cached.output)
        assertEquals(TaskOutcome.UP_TO_DATE, run("generateOntology").task(":generateOntologySample")?.outcome)
        File(dir, "context.json").writeText("""{"@context":{"ex":"https://example.test/","Renamed":"ex:Person","Old":"ex:Old"}}""")
        assertEquals(TaskOutcome.SUCCESS, run("generateOntology").task(":generateOntologySample")?.outcome)
        val output = File(dir, "build/generated/sources/kastor-gen/sample")
        assertTrue(File(output, "demo/domain/Renamed.kt").exists())
        assertFalse(File(output, "demo/domain/Human.kt").exists())
        val shapes = File(dir, "shapes.ttl")
        shapes.writeText(shapes.readText().substringBefore("ex:OldShape"))
        run("generateOntology")
        assertFalse(File(output, "demo/domain/Old.kt").exists())
        assertFalse(File(output, "demo/impl/OldWrapper.kt").exists())
    }
}
