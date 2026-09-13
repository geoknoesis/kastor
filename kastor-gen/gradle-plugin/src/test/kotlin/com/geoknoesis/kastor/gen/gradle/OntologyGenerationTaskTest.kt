package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fast, in-process checks of plugin wiring, defaults and generation edge cases (no TestKit daemon). */
class OntologyGenerationTaskTest {

    @TempDir
    lateinit var dir: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix dct: <http://purl.org/dc/terms/> .
        @prefix dcat: <http://www.w3.org/ns/dcat#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path dct:title ; sh:name "Title" ; sh:datatype xsd:string ; sh:maxCount 1 ;
                          sh:description "Line one\nwith \"quotes\", ${'$'}graph, 100% and image/* */" ] ;
            sh:property [ sh:path dcat:title ; sh:name "dcat-title" ; sh:datatype xsd:string ; sh:maxCount 1 ] .
        [] a sh:NodeShape ; sh:targetClass dcat:Catalog ; sh:property "literal is not a property shape" .
    """.trimIndent()

    private fun write(name: String, text: String) = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }

    private fun task(configure: OntologyConfig.() -> Unit): OntologyGenerationTask {
        write("shapes.ttl", shapes)
        if (!File(dir, "dcat-us_3.0_context.jsonld").exists()) {
            write("dcat-us_3.0_context.jsonld", """{"@context":{"ex":"https://example.test/","dct":"http://purl.org/dc/terms/","dcat":"http://www.w3.org/ns/dcat#"}}""")
        }
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.pluginManager.apply(OntoMapperPlugin::class.java)
        val extension = project.extensions.getByType(OntoMapperExtension::class.java)
        extension.ontologies!!.create("sample").apply {
            shaclPath = "shapes.ttl"
            contextPath = "dcat-us_3.0_context.jsonld"
            configure()
        }
        return project.tasks.getByName("generateOntologySample") as OntologyGenerationTask
    }

    private fun output(t: OntologyGenerationTask): File = t.outputDirectory.get().asFile

    @Test
    fun `optional settings default relative to interfacePackage`() {
        val t = task {
            interfacePackage = "demo.domain"
            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
            generateDsl = true
        }
        t.generateOntology()
        val out = output(t)
        assertTrue(File(out, "demo/domain/Person.kt").isFile)
        assertTrue(File(out, "demo/domain/PersonWrapper.kt").isFile)
        assertTrue(File(out, "demo/domain/DCAT.kt").isFile, "vocabulary is auto-enabled when its metadata is set")
        assertTrue(File(out, "demo/domain/dsl/DcatUs30ContextDsl.kt").isFile, "DSL name is derived as a valid identifier")
        assertFalse(File(out, "interfaces").exists() || File(out, "wrappers").exists() || File(out, "dsl").exists())
    }

    @Test
    fun `vocabulary keeps full IRIs for out-of-namespace terms and qualifies clashing names`() {
        val t = task {
            interfacePackage = "demo.domain"
            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
        }
        t.generateOntology()
        val vocabulary = File(output(t), "demo/domain/DCAT.kt").readText()
        assertTrue(vocabulary.contains("public val title: Iri by lazy { term(\"title\") }"), vocabulary)
        assertTrue(vocabulary.contains("public val dct_title: Iri by lazy { Iri(\"http://purl.org/dc/terms/title\") }"), vocabulary)
        assertTrue(vocabulary.contains("term(\"Catalog\")"), vocabulary)
        assertFalse(vocabulary.contains("image/* */"), vocabulary)
    }

    @Test
    fun `missing or invalid packages fail with a clear message`() {
        val missing = assertFailsWith<GradleException> { task { }.generateOntology() }
        assertTrue(missing.message!!.contains("interfacePackage"), missing.message)
        val invalid = assertFailsWith<GradleException> { task { interfacePackage = "demo.1bad" }.generateOntology() }
        assertTrue(invalid.message!!.contains("demo.1bad"), invalid.message)
    }

    @Test
    fun `a SHACL syntax error fails the task and keeps the previous output`() {
        val first = task { interfacePackage = "demo.domain" }
        first.generateOntology()
        val person = File(output(first), "demo/domain/Person.kt")
        assertTrue(person.isFile)

        val second = task { interfacePackage = "demo.domain" }
        write("shapes.ttl", "@prefix sh: <http://www.w3.org/ns/shacl#> . this is not turtle")
        val e = assertFailsWith<GradleException> { second.generateOntology() }
        assertTrue(e.message!!.contains("SHACL"), e.message)
        assertTrue(person.isFile, "previous output must not be swept when generation fails")
    }

    @Test
    fun `case-only renames replace the file on every file system`() {
        write("dcat-us_3.0_context.jsonld", """{"@context":{"ex":"https://example.test/","Foo":"ex:Person"}}""")
        val first = task { interfacePackage = "demo.domain" }
        first.generateOntology()
        val pkg = File(output(first), "demo/domain")
        assertTrue(pkg.list()!!.contains("Foo.kt"))

        write("dcat-us_3.0_context.jsonld", """{"@context":{"ex":"https://example.test/","FOO":"ex:Person"}}""")
        task { interfacePackage = "demo.domain" }.generateOntology()
        val names = pkg.list()!!.toSet()
        assertTrue("FOO.kt" in names && "FOOWrapper.kt" in names, names.toString())
        assertFalse("Foo.kt" in names || "FooWrapper.kt" in names, names.toString())
    }

    @Test
    fun `separate wrapper package gets a KotlinPoet factory stub next to the interface`() {
        val t = task {
            interfacePackage = "demo.domain"
            wrapperPackage = "demo.impl"
        }
        t.generateOntology()
        val stub = File(output(t), "demo/domain/PersonFactory.kt").readText()
        assertTrue(stub.contains("Class.forName(\"demo.impl.PersonWrapper\")"), stub)
        assertEquals("package demo.domain", stub.lines().first { it.startsWith("package") })
    }
}
