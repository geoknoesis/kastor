package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyProcessor
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSValueArgument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives [OntologyProcessor] through several KSP rounds with a minimal in-memory KSP environment: symbols that
 * were already generated are never generated again, ontology files read from resources produce a staleness
 * warning, and relative `kastor.gen.resources` roots are resolved against the project directory.
 */
class OntologyProcessorRoundsTest {

    @TempDir
    lateinit var root: File

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:name "name" ; sh:datatype xsd:string ; sh:maxCount 1 ] .
    """.trimIndent()

    private inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "${T::class.simpleName}-proxy"
                else -> handler(method.name, args ?: emptyArray())
            }
        } as T

    private fun name(value: String): KSName = proxy { m, _ ->
        when (m) {
            "asString", "getShortName" -> value
            "getQualifier" -> ""
            else -> throw UnsupportedOperationException(m)
        }
    }

    private fun argument(key: String, value: Any): KSValueArgument = proxy { m, _ ->
        when (m) {
            "getName" -> name(key)
            "getValue" -> value
            else -> throw UnsupportedOperationException(m)
        }
    }

    private fun annotatedFile(path: File, vararg args: Pair<String, Any>): KSFile {
        // The processor identifies @Rdf by the qualified name of the resolved annotation class, not by its short name.
        val annotationClass: com.google.devtools.ksp.symbol.KSDeclaration = proxy { m, _ ->
            when (m) {
                "getQualifiedName" -> name("com.geoknoesis.kastor.gen.annotations.Rdf")
                else -> throw UnsupportedOperationException(m)
            }
        }
        val annotationType: com.google.devtools.ksp.symbol.KSType = proxy { m, _ ->
            when (m) {
                "getDeclaration" -> annotationClass
                else -> throw UnsupportedOperationException(m)
            }
        }
        val annotationTypeReference: com.google.devtools.ksp.symbol.KSTypeReference = proxy { m, _ ->
            when (m) {
                "resolve" -> annotationType
                else -> throw UnsupportedOperationException(m)
            }
        }
        val annotation: KSAnnotation = proxy { m, _ ->
            when (m) {
                "getShortName" -> name("Rdf")
                "getAnnotationType" -> annotationTypeReference
                "getArguments" -> args.map { (k, v) -> argument(k, v) }
                else -> throw UnsupportedOperationException(m)
            }
        }
        return proxy { m, _ ->
            when (m) {
                "getAnnotations" -> sequenceOf(annotation)
                "getPackageName" -> name("gen.rounds")
                "getFilePath" -> path.absolutePath
                "getFileName" -> path.name
                "getDeclarations" -> emptySequence<Any>()
                else -> throw UnsupportedOperationException(m)
            }
        }
    }

    private class Files {
        val created = mutableListOf<String>()
        val generator: CodeGenerator = Proxy.newProxyInstance(CodeGenerator::class.java.classLoader, arrayOf(CodeGenerator::class.java)) { _, method, args ->
            when (method.name) {
                "createNewFile" -> {
                    val key = "${args[1]}.${args[2]}"
                    if (key in created) throw kotlin.io.FileAlreadyExistsException(File(key))
                    created += key
                    ByteArrayOutputStream()
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as CodeGenerator
    }

    private fun resolver(vararg symbols: KSAnnotated): Resolver = proxy { m, _ ->
        when (m) {
            "getSymbolsWithAnnotation" -> symbols.asSequence()
            else -> throw UnsupportedOperationException(m)
        }
    }

    private fun sourceTree(): File {
        File(root, "src/main/resources/shapes.ttl").apply { parentFile.mkdirs(); writeText(shapes) }
        return File(root, "src/main/kotlin/gen/rounds/Anchor.kt").apply { parentFile.mkdirs(); writeText("package gen.rounds") }
    }

    @Test
    fun `symbols generated in an earlier round are not generated again`() {
        val files = Files()
        var round = 0
        // Round 1: the annotated file references types that do not exist yet, so it does not validate.
        val processor = OntologyProcessor(files.generator, RecordingLogger(), emptyMap()) { round > 1 }
        val symbol = annotatedFile(sourceTree(), "shacl" to "shapes.ttl", "packageName" to "gen.rounds")

        round = 1
        assertEquals(emptyList(), processor.process(resolver(symbol)))
        val generated = files.created.toList()
        assertTrue("gen.rounds.Person" in generated && "gen.rounds.PersonWrapper" in generated, generated.toString())

        round = 2
        assertEquals(emptyList(), processor.process(resolver(symbol)))
        assertEquals(generated, files.created)
    }

    @Test
    fun `ontology files read by KSP produce a staleness warning unless declared tracked`() {
        val symbol = annotatedFile(sourceTree(), "shacl" to "shapes.ttl", "packageName" to "gen.rounds")

        val logger = RecordingLogger()
        OntologyProcessor(Files().generator, logger, emptyMap()).process(resolver(symbol))
        assertTrue(logger.warnings.any { "shapes.ttl" in it && "com.geoknoesis.kastor.gen" in it }, logger.warnings.toString())

        val tracked = RecordingLogger()
        OntologyProcessor(Files().generator, tracked, mapOf(OntologyProcessor.RESOURCES_TRACKED_OPTION to "true")).process(resolver(symbol))
        assertTrue(tracked.warnings.none { "shapes.ttl" in it }, tracked.warnings.toString())
    }

    @Test
    fun `relative resource roots resolve against the project directory and fail loudly otherwise`() {
        File(root, "ontology/shapes.ttl").apply { parentFile.mkdirs(); writeText(shapes) }
        val detached = File(root, "elsewhere/Anchor.kt").apply { parentFile.mkdirs(); writeText("package gen.rounds") }
        val symbol = annotatedFile(detached, "shacl" to "shapes.ttl", "packageName" to "gen.rounds")

        val files = Files()
        val options = mapOf(
            OntologyProcessor.RESOURCES_OPTION to "ontology",
            OntologyProcessor.PROJECT_DIR_OPTION to root.absolutePath,
        )
        OntologyProcessor(files.generator, RecordingLogger(), options).process(resolver(symbol))
        assertTrue("gen.rounds.Person" in files.created, files.created.toString())

        val e = assertFailsWith<InvalidConfigurationException> {
            OntologyProcessor(Files().generator, RecordingLogger(), mapOf(OntologyProcessor.RESOURCES_OPTION to "no-such-dir-kastor"))
                .process(resolver(symbol))
        }
        assertTrue(e.message!!.contains("no-such-dir-kastor") && e.message!!.contains(OntologyProcessor.PROJECT_DIR_OPTION), e.message)
    }
}
