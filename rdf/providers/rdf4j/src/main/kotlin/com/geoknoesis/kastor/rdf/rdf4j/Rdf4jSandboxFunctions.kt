package com.geoknoesis.kastor.rdf.rdf4j

import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry

/**
 * The functions of RDF4J's global [FunctionRegistry] that a sandboxed query may call: RDF4J's own implementations of
 * the SPARQL built-ins and XSD casts, never a function that other code (an application, a library on the classpath)
 * registered, which may have side effects or be unbounded.
 *
 * A registered function counts as standard when its class is one of RDF4J's built-in function packages and comes from
 * the same jar as the registry itself (`rdf4j-queryalgebra-evaluation`). Those implement the SPARQL 1.1 / 1.2
 * functions and operators RDF4J parses into function calls (string, numeric, date-time, hash and RDF-term functions,
 * `UUID` / `STRUUID`, the triple-term functions) and the XSD casts: pure, or as non-deterministic as SPARQL makes them
 * (`NOW`, `RAND`, `UUID`), and bounded by their arguments. The pure helpers `ShaclSail` registers for its own
 * generated queries (from its own jar) are allowed too.
 */
internal object Rdf4jSandboxFunctions {

    private const val BUILT_INS = "org.eclipse.rdf4j.query.algebra.evaluation.function."

    /** The packages of RDF4J's SPARQL built-in function implementations. */
    private val STANDARD_PACKAGES: Set<String> =
        listOf("datetime", "hash", "numeric", "rdfterm", "string", "xsd", "triple").map { BUILT_INS + it }.toSet()

    private val REGISTRY_SOURCE = FunctionRegistry::class.java.protectionDomain?.codeSource?.location

    /** The jar of RDF4J's `ShaclSail`, whose own pure helper functions its generated queries call. */
    private val SHACL_SOURCE = org.eclipse.rdf4j.sail.shacl.ShaclSail::class.java.protectionDomain?.codeSource?.location

    /**
     * Whether a sandboxed query may call the function that RDF4J's registry holds for [iri]: one of RDF4J's built-ins,
     * or one of the pure helpers `ShaclSail` registers for the queries it generates (such as its XSD datatype check),
     * which a query on a SHACL store must be able to call.
     */
    fun isStandard(iri: String): Boolean {
        val function = FunctionRegistry.getInstance().get(iri).orElse(null) ?: return false
        val type = function.javaClass
        val source = type.protectionDomain?.codeSource?.location
        return (type.packageName in STANDARD_PACKAGES && source == REGISTRY_SOURCE) ||
            (type.packageName.startsWith(SHACL_PACKAGE) && source != null && source == SHACL_SOURCE)
    }

    private const val SHACL_PACKAGE = "org.eclipse.rdf4j.sail.shacl"
}
