package com.geoknoesis.kastor.gen.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
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

    private fun run(vararg args: String) = ConsumerBuild.runner(dir, *args)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `a file appearing at the preferred location is used although the configuration cache is reused`() {
        File(dir, "settings.gradle").writeText(ConsumerBuild.settings("inputs"))
        File(dir, "gradle.properties").writeText(ConsumerBuild.properties)
        File(dir, "build.gradle.kts").writeText("""
            import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask

            plugins {
                kotlin("jvm")
                id("com.geoknoesis.kastor.gen")
            }
            kastorGen {
                ontologies {
                    create("sample") {
                        shaclPath = "shapes.ttl"
                        interfacePackage = "demo.domain"
                    }
                }
            }
            // Build logic that reads the convention of shaclFile, at execution time.
            tasks.register("printShacl") {
                val shaclFile = tasks.named<OntologyGenerationTask>("generateOntologySample").flatMap { it.shaclFile }
                val root = layout.projectDirectory.asFile
                doLast { println("SHACL=" + shaclFile.get().asFile.relativeTo(root).invariantSeparatorsPath) }
            }
        """.trimIndent())
        val fallback = File(dir, "src/main/resources/shapes.ttl").apply { parentFile.mkdirs(); writeText(shapes("Fallback")) }
        val output = File(dir, "build/generated/sources/kastor-gen/sample")

        val first = run("generateOntology", "printShacl").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateOntologySample")?.outcome)
        assertTrue(File(output, "demo/domain/Fallback.kt").exists(), "resolved from src/main/resources")
        assertTrue("SHACL=src/main/resources/shapes.ttl" in first.output, "shaclFile.get() is the file the task reads")

        // The project-directory location takes precedence as soon as a file exists there.
        val preferred = File(dir, "shapes.ttl").apply { writeText(shapes("Preferred")) }
        val second = run("generateOntology", "printShacl").build()
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertTrue("SHACL=shapes.ttl" in second.output, "the convention is not frozen in the configuration cache: " + second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":generateOntologySample")?.outcome)
        assertTrue(File(output, "demo/domain/Preferred.kt").exists(), "the new file in the project directory is used")
        assertFalse(File(output, "demo/domain/Fallback.kt").exists())

        // ... and the fallback is used again once it is gone.
        preferred.delete()
        val third = run("generateOntology", "printShacl").build()
        assertTrue(third.output.contains("Reusing configuration cache"), third.output)
        assertTrue("SHACL=src/main/resources/shapes.ttl" in third.output, third.output)
        assertTrue(File(output, "demo/domain/Fallback.kt").exists())
        assertFalse(File(output, "demo/domain/Preferred.kt").exists())

        // Without a file at either location the task fails naming both.
        fallback.delete()
        val failed = run("generateOntology").buildAndFail()
        assertTrue(failed.output.contains("shapes.ttl") && failed.output.contains("src/main/resources"), failed.output)
    }
}
