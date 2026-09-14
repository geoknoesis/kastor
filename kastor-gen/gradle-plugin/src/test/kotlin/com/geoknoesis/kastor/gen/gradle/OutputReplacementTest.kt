package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.gradle.tasks.FileReplacement
import com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Replacing generated output survives transient file locks and never leaves untracked files after a failure. */
class OutputReplacementTest {

    @TempDir
    lateinit var dir: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:datatype xsd:string ; sh:maxCount 1 ] .
    """.trimIndent()

    private val move: (Path, Path) -> Unit = { s, t -> Files.move(s, t, StandardCopyOption.REPLACE_EXISTING) }

    /** A task generating `demo.domain.<typeName>` (and its wrapper) from the Person shape. */
    private fun task(typeName: String, replacement: FileReplacement = FileReplacement(sleeper = {})): OntologyGenerationTask {
        File(dir, "shapes.ttl").writeText(shapes)
        File(dir, "context.jsonld").writeText("""{"@context":{"ex":"https://example.test/","$typeName":"ex:Person"}}""")
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.pluginManager.apply(OntoMapperPlugin::class.java)
        project.extensions.getByType(OntoMapperExtension::class.java).ontologies!!.create("sample").apply {
            shaclPath = "shapes.ttl"
            contextPath = "context.jsonld"
            interfacePackage = "demo.domain"
        }
        return (project.tasks.getByName("generateOntologySample") as OntologyGenerationTask).also { it.fileReplacement = replacement }
    }

    private fun pkg(t: OntologyGenerationTask) = File(t.outputDirectory.get().asFile, "demo/domain")
    private fun manifest(t: OntologyGenerationTask) = File(t.outputDirectory.get().asFile, ".kastor-generated-files").readLines().filter { it.isNotBlank() }

    @Test
    fun `a transiently locked file is retried with backoff until the move succeeds`() {
        var failures = 2
        val waits = mutableListOf<Long>()
        val t = task("Foo", FileReplacement(mover = { s, target ->
            if (target.fileName.toString() == "Foo.kt" && failures-- > 0) throw AccessDeniedException(target.toString())
            move(s, target)
        }, sleeper = { waits += it }))
        t.generateOntology()
        assertEquals(setOf("Foo.kt", "FooWrapper.kt"), pkg(t).list()!!.toSet())
        assertEquals(listOf("demo/domain/Foo.kt", "demo/domain/FooWrapper.kt"), manifest(t))
        assertEquals(listOf(50L, 100L), waits)
    }

    @Test
    fun `a move that keeps failing fails the task and the next run removes every file written so far`() {
        task("Foo").generateOntology()

        val locked = task("Bar", FileReplacement(mover = { s, target ->
            if (target.fileName.toString() == "BarWrapper.kt") throw FileSystemException(target.toString(), null, "being used by another process")
            move(s, target)
        }, sleeper = {}))
        val e = assertFailsWith<GradleException> { locked.generateOntology() }
        assertTrue(e.message!!.contains("re-run", ignoreCase = true), e.message)
        assertTrue(File(pkg(locked), "Bar.kt").isFile, "files moved before the failure exist")
        assertTrue("demo/domain/Bar.kt" in manifest(locked) && "demo/domain/Foo.kt" in manifest(locked), manifest(locked).toString())

        val next = task("Baz")
        next.generateOntology()
        assertEquals(setOf("Baz.kt", "BazWrapper.kt"), pkg(next).list()!!.toSet(), "no stale Foo/Bar files remain")
        assertEquals(listOf("demo/domain/Baz.kt", "demo/domain/BazWrapper.kt"), manifest(next))
    }

    @Test
    fun `moves across file stores copy next to the target and keep the previous target on failure`() {
        val source = File(dir, "staging/A.kt").apply { parentFile.mkdirs(); writeText("new") }.toPath()
        val target = File(dir, "out/A.kt").apply { parentFile.mkdirs(); writeText("old") }.toPath()

        assertFailsWith<IOException> {
            FileReplacement(mover = { _, t -> throw AccessDeniedException(t.toString()) }, sleeper = {}).move(source, target, sameFileStore = false)
        }
        assertEquals("old", Files.readString(target))
        assertTrue(Files.exists(source))
        assertEquals(listOf("A.kt"), target.parent.toFile().list()!!.toList(), "no staged copy is left behind")

        FileReplacement(sleeper = {}).move(source, target, sameFileStore = false)
        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(source))
        assertEquals(listOf("A.kt"), target.parent.toFile().list()!!.toList())
    }
}
