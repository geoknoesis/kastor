package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessorProvider
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Kotlin block of the Kastor Gen tutorials that sits under a heading naming a **complete example** is run
 * through the processor (KSP), compiled and its `main` run: the getting-started tutorial's complete example did not
 * compile, as its `@Rdf` resolved to the object `com.geoknoesis.kastor.rdf.Rdf` rather than the annotation.
 */
class TutorialExamplesTest {
    private val tutorials: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "docs/kastor-gen/tutorials").isDirectory }
        .resolve("docs/kastor-gen/tutorials")

    /** The Kotlin blocks under a heading that contains "complete example" (any case), with their heading. */
    private fun completeExamples(markdown: String): List<Pair<String, String>> {
        val blocks = ArrayList<Pair<String, String>>()
        var heading = ""
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#")) heading = line
            if (line.trim() == "```kotlin" && heading.contains("complete example", ignoreCase = true)) {
                val end = (i + 1 until lines.size).first { lines[it].trim() == "```" }
                blocks += heading to lines.subList(i + 1, end).joinToString("\n")
                i = end
            }
            i++
        }
        return blocks
    }

    @Test
    fun `the complete examples of the tutorials compile and run`() {
        val examples = tutorials.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }
            .flatMap { file -> completeExamples(file.readText()).map { (heading, code) -> Triple(file.name, heading, code) } }
        assertTrue(examples.any { it.first == "getting-started.md" }, "no complete example found in getting-started.md")
        for ((file, heading, code) in examples) {
            val packageName = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(code)?.groupValues?.get(1) ?: ""
            val path = packageName.replace('.', '/').let { if (it.isEmpty()) "Example.kt" else "$it/Example.kt" }
            val result = KspRunner.run(OntoMapperProcessorProvider(), mapOf(path to code))
            assertEquals(emptyList(), result.errors, "$file / $heading")
            val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources)
            check(compiled.ok) { "$file / $heading does not compile:\n${compiled.output}" }
            val main = compiled.classLoader().loadClass(if (packageName.isEmpty()) "ExampleKt" else "$packageName.ExampleKt").getMethod("main")
            val output = ByteArrayOutputStream()
            val stdout = System.out
            System.setOut(PrintStream(output, true, "UTF-8"))
            try {
                main.invoke(null)
            } finally {
                System.setOut(stdout)
            }
            if (file == "getting-started.md") assertTrue(output.toString("UTF-8").contains("Name: Alice Johnson"), output.toString("UTF-8"))
        }
    }
}
