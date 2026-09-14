package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.originatingFiles
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * A wrapper generated for a hand-written `@Rdf` interface depends on the interface's file and the files declaring
 * its supertypes (whose properties it implements), not on every source file of the compilation.
 */
class OntoMapperOriginatingFilesTest {

    private inline fun <reified T> proxy(label: String, crossinline handler: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> label
                else -> handler(method.name)
            }
        } as T

    private fun file(name: String): KSFile = proxy(name) { throw UnsupportedOperationException(it) }

    private fun declaration(name: String, file: KSFile?, vararg supers: KSClassDeclaration): KSClassDeclaration = proxy(name) { m ->
        when (m) {
            "getContainingFile" -> file
            "getSuperTypes" -> supers.asSequence().map { parent ->
                proxy<KSTypeReference>("ref-$name") { r ->
                    when (r) {
                        "resolve" -> proxy<KSType>("type-$name") { t -> if (t == "getDeclaration") parent else throw UnsupportedOperationException(t) }
                        else -> throw UnsupportedOperationException(r)
                    }
                }
            }
            else -> throw UnsupportedOperationException(m)
        }
    }

    @Test
    fun `originating files are the declaration file and its supertypes files`() {
        val agentFile = file("Agent.kt")
        val personFile = file("Person.kt")
        val any = declaration("kotlin.Any", null)
        val named = declaration("Named", agentFile, any)
        val agent = declaration("Agent", agentFile, named)
        val person = declaration("Person", personFile, agent, any)

        assertEquals(listOf(personFile, agentFile), originatingFiles(person))
    }
}
