package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.SparqlSelect
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class GenerationConsumerTest {
    @TempDir lateinit var dir: File
    @Test fun `consumer compiles renamed cross package types and regenerates changed inputs`() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'consumer'")
        File(dir, "gradle.properties").writeText("""
            org.gradle.workers.max=1
            org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=2
            kotlin.compiler.execution.strategy=in-process
            kotlin.internal.collectFUSMetrics=false
        """.trimIndent())
        val classpath = listOf(Rdf::class.java, RdfHandle::class.java, SparqlSelect::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).invariantSeparatorsPath }.distinct()
            .joinToString(",") { "\"$it\"" }
        File(dir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm") version "2.4.20"
                id("com.geoknoesis.kastor.gen")
                application
            }
            repositories { mavenCentral() }
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
            ex:PersonShape a sh:NodeShape; sh:targetClass ex:Person;
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
                println("consumer-ok")
            }
        """.trimIndent())
        fun run(vararg args: String) = GradleRunner.create().withProjectDir(dir).withPluginClasspath().forwardOutput()
            .withEnvironment(System.getenv() + ("JAVA_HOME" to System.getProperty("java.home")))
            .withArguments(*args, "--stacktrace", "--no-build-cache", "--configuration-cache", "--configuration-cache-problems=fail").build()
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
