package com.geoknoesis.kastor.gen.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A relative `shaclPath` is looked up in the project directory and then in `src/main/resources`. Both locations are
 * task inputs and the lookup happens when the task runs, so a file that appears at (or disappears from) the preferred
 * location is picked up even when the configuration is reused from the configuration cache.
 */
class OntologyInputResolutionTest {
    @TempDir lateinit var dir: File

    private fun shapes(cls: String) = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:${cls}Shape a sh:NodeShape; sh:targetClass ex:$cls;
            sh:property [ sh:path ex:title; sh:name "title"; sh:datatype xsd:string; sh:maxCount 1 ] .
    """.trimIndent()

    private fun run(vararg args: String) = GradleRunner.create().withProjectDir(dir).withTestKitDir(testKitDir()).withPluginClasspath().forwardOutput()
        .withEnvironment(System.getenv() + ("JAVA_HOME" to System.getProperty("java.home")))
        .withArguments(*args, "--stacktrace", "--no-build-cache", "--configuration-cache", "--configuration-cache-problems=fail")

    @Test
    fun `a file appearing at the preferred location is used although the configuration cache is reused`() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'inputs'")
        File(dir, "gradle.properties").writeText("""
            org.gradle.workers.max=1
            org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2
            kotlin.compiler.execution.strategy=in-process
            kotlin.internal.collectFUSMetrics=false
        """.trimIndent())
        File(dir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm") version "2.4.20"
                id("com.geoknoesis.kastor.gen")
            }
            repositories { mavenCentral() }
            kastorGen {
                ontologies {
                    create("sample") {
                        shaclPath = "shapes.ttl"
                        interfacePackage = "demo.domain"
                    }
                }
            }
        """.trimIndent())
        val fallback = File(dir, "src/main/resources/shapes.ttl").apply { parentFile.mkdirs(); writeText(shapes("Fallback")) }
        val output = File(dir, "build/generated/sources/kastor-gen/sample")

        assertEquals(TaskOutcome.SUCCESS, run("generateOntology").build().task(":generateOntologySample")?.outcome)
        assertTrue(File(output, "demo/domain/Fallback.kt").exists(), "resolved from src/main/resources")

        // The project-directory location takes precedence as soon as a file exists there.
        val preferred = File(dir, "shapes.ttl").apply { writeText(shapes("Preferred")) }
        val second = run("generateOntology").build()
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":generateOntologySample")?.outcome)
        assertTrue(File(output, "demo/domain/Preferred.kt").exists(), "the new file in the project directory is used")
        assertFalse(File(output, "demo/domain/Fallback.kt").exists())

        // ... and the fallback is used again once it is gone.
        preferred.delete()
        val third = run("generateOntology").build()
        assertTrue(third.output.contains("Reusing configuration cache"), third.output)
        assertTrue(File(output, "demo/domain/Fallback.kt").exists())
        assertFalse(File(output, "demo/domain/Preferred.kt").exists())

        // Without a file at either location the task fails naming both.
        fallback.delete()
        val failed = run("generateOntology").buildAndFail()
        assertTrue(failed.output.contains("shapes.ttl") && failed.output.contains("src/main/resources"), failed.output)
    }
}
