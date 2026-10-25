package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.RdfResource
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The entry points Java code calls are static methods, and the report serialization has a no-argument overload. */
class JavaInteropTest {

    @Test
    fun `the facade and the registry are static from Java`() {
        for (type in listOf(ShaclValidation::class.java, ValidatorRegistry::class.java)) {
            val publicMethods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            assertTrue(publicMethods.isNotEmpty())
            for (method in publicMethods) assertTrue(Modifier.isStatic(method.modifiers), "${type.simpleName}.${method.name} is not static")
        }
    }

    @Test
    fun `toShaclValidationReportRdf can be called without a report node`() {
        val holder = Class.forName("com.geoknoesis.kastor.rdf.shacl.ValidationReportRdfKt")
        holder.getMethod("toShaclValidationReportRdf", ValidationReport::class.java)
        holder.getMethod("toShaclValidationReportRdf", ValidationReport::class.java, RdfResource::class.java)
    }
}
