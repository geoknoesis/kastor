package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Output replacement safety, optional context and Kotlin source-set wiring (in-process, no TestKit daemon). */
class PluginRobustnessTest {

    @TempDir
    lateinit var dir: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:name "name" ; sh:datatype xsd:string ; sh:maxCount 1 ] .
        ex:PlaceShape a sh:NodeShape ; sh:targetClass ex:Place ;
            sh:property [ sh:path ex:label ; sh:name "label" ; sh:datatype xsd:string ; sh:maxCount 1 ] .
    """.trimIndent()

    private fun project(): Project {
        File(dir, "shapes.ttl").writeText(shapes)
        File(dir, "context.jsonld").writeText("""{"@context":{"ex":"https://example.test/"}}""")
        return ProjectBuilder.builder().withProjectDir(dir).build().also { it.pluginManager.apply(OntoMapperPlugin::class.java) }
    }

    private fun task(configure: OntologyConfig.() -> Unit = {}): OntologyGenerationTask {
        val project = project()
        project.extensions.getByType(OntoMapperExtension::class.java).ontologies!!.create("sample").apply {
            shaclPath = "shapes.ttl"
            contextPath = "context.jsonld"
            interfacePackage = "demo.domain"
            configure()
        }
        return project.tasks.getByName("generateOntologySample") as OntologyGenerationTask
    }

    @Test
    fun `an IO failure while writing leaves the previous output and manifest untouched`() {
        val first = task()
        first.generateOntology()
        val out = first.outputDirectory.get().asFile
        val manifest = File(out, ".kastor-generated-files")
        val before = out.walkTopDown().filter { it.isFile }.associate { it.relativeTo(out).path to it.readText() }
        assertTrue(before.keys.any { it.endsWith("Person.kt") }, before.keys.toString())

        val second = task { interfacePackage = "demo.renamed" }
        var writes = 0
        second.beforeFileWritten = { if (++writes == 2) throw IOException("disk full") }
        val e = assertFailsWith<GradleException> { second.generateOntology() }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("disk full") == true }, e.message)

        val after = out.walkTopDown().filter { it.isFile }.associate { it.relativeTo(out).path to it.readText() }
        assertEquals(before, after)
        assertTrue(manifest.readText().contains("demo/domain/Person.kt"))
    }

    @Test
    fun `an escaping manifest entry fails before any previous file is deleted`() {
        val first = task()
        first.generateOntology()
        val out = first.outputDirectory.get().asFile
        val manifest = File(out, ".kastor-generated-files")
        manifest.writeText(manifest.readText() + "\n../../outside.kt")

        val e = assertFailsWith<GradleException> { task().generateOntology() }
        assertTrue(e.message!!.contains("escapes"), e.message)
        assertTrue(File(out, "demo/domain/Person.kt").isFile && File(out, "demo/domain/Place.kt").isFile)
    }

    @Test
    fun `the JSON-LD context is optional`() {
        val t = task { contextPath = "" }
        t.generateOntology()
        assertTrue(File(t.outputDirectory.get().asFile, "demo/domain/Person.kt").isFile)
    }

    @Test
    fun `a project without a Kotlin JVM or multiplatform plugin fails with a clear message`() {
        val project = project()
        project.extensions.getByType(OntoMapperExtension::class.java).ontologies!!.create("sample").apply {
            shaclPath = "shapes.ttl"
            interfacePackage = "demo.domain"
        }
        val e = assertFailsWith<Exception> { (project as ProjectInternal).evaluate() }
        assertTrue(
            generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("org.jetbrains.kotlin.jvm") },
            e.toString(),
        )
    }

    // ---- Kotlin Multiplatform wiring against stand-ins with the KGP accessor names ----

    @Suppress("EnumEntryName")
    enum class FakePlatformType { common, jvm, androidJvm }

    class FakeSourceSet(project: Project, name: String) {
        val kotlin: SourceDirectorySet = project.objects.sourceDirectorySet(name, name)
    }

    class FakeCompilation(private val compilationName: String, val defaultSourceSet: FakeSourceSet) : Named {
        override fun getName(): String = compilationName
    }

    class FakeTarget(private val targetName: String, val platformType: FakePlatformType, val compilations: NamedDomainObjectContainer<FakeCompilation>) : Named {
        override fun getName(): String = targetName
    }

    class FakeKotlinExtension(val targets: NamedDomainObjectContainer<FakeTarget>)

    @Test
    fun `multiplatform wiring adds generated sources to the main compilation of every JVM target`() {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val sourceSets = mutableMapOf<String, FakeSourceSet>()
        fun target(name: String, type: FakePlatformType): FakeTarget {
            val compilations = project.objects.domainObjectContainer(FakeCompilation::class.java) { compilation ->
                FakeCompilation(compilation, FakeSourceSet(project, "$name${compilation.replaceFirstChar { it.uppercaseChar() }}").also { sourceSets["$name/$compilation"] = it })
            }
            compilations.create("main")
            compilations.create("test")
            return FakeTarget(name, type, compilations)
        }
        val targets = project.objects.domainObjectContainer(FakeTarget::class.java)
        val generated = project.layout.buildDirectory.dir("generated/kastor")
        KotlinSourceSetWiring.wireMultiplatform(project, FakeKotlinExtension(targets), generated)
        targets.add(target("desktop", FakePlatformType.jvm))
        targets.add(target("metadata", FakePlatformType.common))
        targets.add(target("android", FakePlatformType.androidJvm))

        val expected = generated.get().asFile
        assertTrue(expected in sourceSets.getValue("desktop/main").kotlin.srcDirs)
        assertFalse(expected in sourceSets.getValue("desktop/test").kotlin.srcDirs)
        assertFalse(expected in sourceSets.getValue("metadata/main").kotlin.srcDirs)
        assertFalse(expected in sourceSets.getValue("android/main").kotlin.srcDirs)
    }
}
