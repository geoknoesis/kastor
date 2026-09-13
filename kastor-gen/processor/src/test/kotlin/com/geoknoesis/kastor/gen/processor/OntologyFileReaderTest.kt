package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.api.exceptions.FileNotFoundException
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyFileReader
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OntologyFileReaderTest {

    @TempDir
    lateinit var dir: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        ex:S a sh:NodeShape ; sh:targetClass ex:Thing .
    """.trimIndent()

    @Test
    fun `relative ontology paths resolve against the annotated file's source set resources`() {
        val source = File(dir, "src/main/kotlin/demo/Model.kt").apply { parentFile.mkdirs(); writeText("package demo") }
        File(dir, "src/main/resources/ontology/shapes.ttl").apply { parentFile.mkdirs(); writeText(shapes) }

        val model = OntologyFileReader(RecordingLogger()).loadOntologyModel("ontology/shapes.ttl", near = source)
        assertEquals(listOf("https://example.test/Thing"), model.shapes.map { it.targetClass })
    }

    @Test
    fun `configured search roots are used and a missing file names the locations tried`() {
        val roots = File(dir, "extra").apply { mkdirs() }
        File(roots, "shapes.ttl").writeText(shapes)
        val reader = OntologyFileReader(RecordingLogger(), listOf(roots))
        assertEquals(1, reader.loadOntologyModel("shapes.ttl").shapes.size)

        val logger = RecordingLogger()
        assertFailsWith<FileNotFoundException> { OntologyFileReader(logger, listOf(roots)).loadOntologyModel("missing.ttl") }
        assertEquals(1, logger.errors.size)
    }
}
