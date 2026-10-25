package com.geoknoesis.kastor.gen.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `kastorGen` settings are lazy Gradle properties: in a consumer build that realizes the generation task before
 * the settings are assigned (one of them from a provider), the late values are used, and the second run reuses the
 * configuration cache.
 */
class LazyConfigurationTest {
    @TempDir lateinit var dir: File

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `late and provider-derived assignments are used and the configuration cache is reused`() {
        File(dir, "settings.gradle").writeText(ConsumerBuild.settings("lazy"))
        File(dir, "gradle.properties").writeText(ConsumerBuild.properties)
        File(dir, "shapes.ttl").writeText("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://example.test/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:PersonShape a sh:NodeShape; sh:targetClass ex:Person;
                sh:property [ sh:path ex:title; sh:name "title"; sh:datatype xsd:string; sh:maxCount 1 ] .
        """.trimIndent())
        File(dir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm")
                id("com.geoknoesis.kastor.gen")
            }
            kastorGen {
                ontologies {
                    create("sample") {
                        shaclPath = "shapes.ttl"
                        interfacePackage = "early.domain"
                    }
                }
            }
            // Realize the task (as tasks.all {} or another plugin would), then assign the settings.
            tasks.getByName("generateOntologySample")
            kastorGen.ontologies.named("sample") {
                interfacePackage = providers.gradleProperty("kastorPackage").orElse("late.domain")
                outputDirectory = layout.projectDirectory.dir("generated")
            }
        """.trimIndent())

        val first = ConsumerBuild.runner(dir, "generateOntologySample").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateOntologySample")?.outcome)
        assertTrue(File(dir, "generated/sample/late/domain/Person.kt").isFile, "the late values are used")
        assertTrue(!File(dir, "generated/sample/early").exists() && !File(dir, "build/generated/sources/kastor-gen").exists())

        val second = ConsumerBuild.runner(dir, "generateOntologySample").build()
        assertTrue("Reusing configuration cache" in second.output, second.output)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateOntologySample")?.outcome)
    }
}
