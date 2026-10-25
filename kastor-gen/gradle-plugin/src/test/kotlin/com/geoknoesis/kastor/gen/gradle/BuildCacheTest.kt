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
 * `OntologyGenerationTask` is `@CacheableTask` with path-relative inputs: a copy of the project in another directory
 * gets the generated sources from the build cache instead of generating them again, and a changed input misses.
 */
class BuildCacheTest {
    @TempDir lateinit var dir: File

    private fun shapes(cls: String) = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:${cls}Shape a sh:NodeShape; sh:targetClass ex:$cls;
            sh:property [ sh:path ex:title; sh:name "title"; sh:datatype xsd:string; sh:maxCount 1 ] .
    """.trimIndent()

    private fun project(name: String, cache: File, cls: String): File = File(dir, name).apply {
        mkdirs()
        File(this, "settings.gradle").writeText(
            ConsumerBuild.settings("cached") + "\nbuildCache { local { directory = file('${cache.toURI()}') } }\n",
        )
        File(this, "gradle.properties").writeText(ConsumerBuild.properties)
        File(this, "build.gradle.kts").writeText("""
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
        """.trimIndent())
        File(this, "shapes.ttl").writeText(shapes(cls))
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `a relocated project is served from the build cache and a changed input is generated again`() {
        val cache = File(dir, "cache")
        val first = project("first", cache, "Person")
        val relocated = project("elsewhere/second", cache, "Person")

        val generated = ConsumerBuild.runner(first, "generateOntologySample", buildCache = true).build()
        assertEquals(TaskOutcome.SUCCESS, generated.task(":generateOntologySample")?.outcome)

        val hit = ConsumerBuild.runner(relocated, "generateOntologySample", buildCache = true).build()
        assertEquals(TaskOutcome.FROM_CACHE, hit.task(":generateOntologySample")?.outcome, hit.output)
        assertTrue(File(relocated, "build/generated/sources/kastor-gen/sample/demo/domain/Person.kt").isFile)

        File(relocated, "shapes.ttl").writeText(shapes("Agent"))
        val miss = ConsumerBuild.runner(relocated, "generateOntologySample", buildCache = true).build()
        assertEquals(TaskOutcome.SUCCESS, miss.task(":generateOntologySample")?.outcome)
        assertTrue(File(relocated, "build/generated/sources/kastor-gen/sample/demo/domain/Agent.kt").isFile)
    }
}
