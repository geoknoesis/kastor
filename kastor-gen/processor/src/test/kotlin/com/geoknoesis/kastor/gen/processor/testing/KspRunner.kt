package com.geoknoesis.kastor.gen.processor.testing

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.io.File
import java.nio.file.Files

/**
 * Runs real KSP (KSP2, in process) over hand-written Kotlin sources, so tests exercise the processors' symbol
 * handling (type resolution, packages, enum detection) instead of hand-built models.
 */
internal object KspRunner {

    class Result(
        val exitCode: KotlinSymbolProcessing.ExitCode,
        val errors: List<String>,
        val warnings: List<String>,
        /** Hand-written sources plus the generated Kotlin files, keyed by relative path, ready to compile. */
        val sources: Map<String, String>,
        val infos: List<String> = emptyList(),
    ) {
        val generated: Map<String, String> get() = sources.filterKeys { it.startsWith(GENERATED) }
    }

    private const val GENERATED = "generated/"

    fun run(provider: SymbolProcessorProvider, sources: Map<String, String>, options: Map<String, String> = emptyMap()): Result =
        run(listOf(provider), sources, options)

    /** Runs several processors in one KSP execution (all rounds), as a consumer applying both would. */
    fun run(providers: List<SymbolProcessorProvider>, sources: Map<String, String>, options: Map<String, String> = emptyMap()): Result {
        val root = Files.createTempDirectory("kastor-ksp").toFile()
        val src = File(root, "src").apply { mkdirs() }
        sources.forEach { (name, code) -> File(src, name).apply { parentFile.mkdirs(); writeText(code) } }
        val out = File(root, "out")
        val kotlinOut = File(out, "kotlin")
        val config = KSPJvmConfig.Builder().apply {
            moduleName = "kastor_ksp_test"
            sourceRoots = listOf(src)
            javaSourceRoots = emptyList()
            commonSourceRoots = emptyList()
            libraries = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).filter { it.exists() }
            friends = emptyList()
            processorOptions = options
            projectBaseDir = root
            outputBaseDir = out
            cachesDir = File(root, "caches")
            classOutputDir = File(out, "classes")
            kotlinOutputDir = kotlinOut
            javaOutputDir = File(out, "java")
            resourceOutputDir = File(out, "resources")
            jdkHome = File(System.getProperty("java.home"))
            jvmTarget = "21"
            languageVersion = "2.3"
            apiVersion = "2.3"
            incremental = false
            modifiedSources = emptyList()
            removedSources = emptyList()
            changedClasses = emptyList()
        }.build()
        val logger = RecordingLogger()
        val exit = KotlinSymbolProcessing(config, providers, logger).execute()
        val generated = kotlinOut.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { GENERATED + it.relativeTo(kotlinOut).invariantSeparatorsPath to it.readText() }
        return Result(exit, logger.errors.toList(), logger.warnings.toList(), sources + generated, logger.infos.toList())
    }
}
