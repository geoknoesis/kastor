package com.geoknoesis.kastor.gen.processor.testing

import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.FileSpec
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Compiles generated [FileSpec]s (plus optional hand-written sources) with the embedded Kotlin
 * compiler against the test runtime classpath, so tests assert that generated code actually
 * compiles instead of only matching substrings.
 */
internal object KotlinSourceCompiler {

    class Result(val ok: Boolean, val output: String, val classesDir: File, val sourcesDir: File) {
        fun sources(): String = sourcesDir.walkTopDown().filter { it.isFile }
            .sortedBy { it.path }
            .joinToString("\n") { "// ---- ${it.relativeTo(sourcesDir).invariantSeparatorsPath}\n${it.readText()}" }

        fun assertOk() {
            check(ok) { "Generated sources failed to compile:\n$output\n\n${sources()}" }
        }

        /** Class loader over the compiled classes, delegating to the test class path. */
        fun classLoader(): ClassLoader =
            URLClassLoader(arrayOf(classesDir.toURI().toURL()), KotlinSourceCompiler::class.java.classLoader)
    }

    fun compile(files: Collection<FileSpec>, extraSources: Map<String, String> = emptyMap()): Result {
        val root = Files.createTempDirectory("kastor-gen-compile").toFile()
        val src = File(root, "src").apply { mkdirs() }
        val out = File(root, "classes").apply { mkdirs() }
        files.forEach { spec ->
            val target = File(src, spec.packageName.replace('.', '/') + "/" + spec.name + ".kt")
            check(!target.exists()) { "Two generated files map to the same path: $target" }
            spec.writeTo(src)
        }
        extraSources.forEach { (name, code) ->
            File(src, name).apply { parentFile.mkdirs(); writeText(code) }
        }
        System.setProperty("idea.io.use.nio2", "true")
        System.setProperty("idea.use.native.fs.for.win", "false")
        val buffer = ByteArrayOutputStream()
        val exit = K2JVMCompiler().exec(
            PrintStream(buffer, true, "UTF-8"),
            src.absolutePath,
            "-d", out.absolutePath,
            "-classpath", System.getProperty("java.class.path"),
            "-no-stdlib", "-no-reflect",
            "-jvm-target", "21",
            "-nowarn",
            "-module-name", "kastor_gen_compile_test",
        )
        return Result(exit.name == "OK", buffer.toString("UTF-8"), out, src)
    }
}

/** KSPLogger that records errors/warnings for assertions. */
internal class RecordingLogger : KSPLogger {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    override fun logging(message: String, symbol: KSNode?) {}
    override fun info(message: String, symbol: KSNode?) {}
    override fun warn(message: String, symbol: KSNode?) { warnings += message }
    override fun error(message: String, symbol: KSNode?) { errors += message }
    override fun exception(e: Throwable) { errors += e.toString() }
}

internal const val XSD_NS = "http://www.w3.org/2001/XMLSchema#"
internal const val EX = "https://example.test/"

internal fun prop(
    local: String,
    name: String = local,
    datatype: String? = "${XSD_NS}string",
    targetClass: String? = null,
    minCount: Int? = null,
    maxCount: Int? = 1,
    description: String = "",
): ShaclProperty = ShaclProperty(
    path = EX + local,
    name = name,
    description = description,
    datatype = if (targetClass != null) null else datatype,
    targetClass = targetClass,
    minCount = minCount,
    maxCount = maxCount,
)
