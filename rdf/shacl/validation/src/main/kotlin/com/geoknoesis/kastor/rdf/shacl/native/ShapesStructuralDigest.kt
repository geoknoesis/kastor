package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.ShapesDigestMode
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * [ShapesDigestMode.SHAPES_STRUCTURAL_DIGEST_V1] — deterministic structural digest (architecture §9.3).
 */
internal object ShapesStructuralDigest {

    @Suppress("DEPRECATION")
    fun digest(shapesTriples: List<RdfTriple>, config: ValidationConfig, budget: ValidationBudget = ValidationBudget.NONE): String =
        when (config.cache.shapesDigestMode) {
            ShapesDigestMode.SHAPES_STRUCTURAL_DIGEST_V1 -> structuralDigestV1(shapesTriples, config, budget)
            ShapesDigestMode.SHAPES_RDF_CANONICAL_DIGEST ->
                throw ShapeCompileException(
                    "SHAPES_RDF_CANONICAL_DIGEST is not implemented yet; use SHAPES_STRUCTURAL_DIGEST_V1",
                )
        }

    private fun structuralDigestV1(triples: List<RdfTriple>, config: ValidationConfig, budget: ValidationBudget): String {
        val profilePart = config.profile.name
        val flagPart =
            listOf(
                config.imports.resolveOwlImports,
                config.strictMode,
                config.allowTripleTermsInShapeParameters,
            ).joinToString(",") { it.toString() }
        // tick() reads the clock only every 64 calls; a clock read per comparison would dominate the sort.
        val rows = triples.map { budget.tick("shape digest"); canonicalTripleRow(it) }
            .sortedWith { a, b -> budget.tick("shape digest sorting"); a.compareTo(b) }
        budget.check("shape digest")
        val md = MessageDigest.getInstance("SHA-256")
        md.update(profilePart.toByteArray(StandardCharsets.UTF_8))
        md.update(0)
        md.update(flagPart.toByteArray(StandardCharsets.UTF_8))
        md.update(0)
        rows.forEach { row ->
            budget.tick("shape digest")
            md.update(row.toByteArray(StandardCharsets.UTF_8))
            md.update(0)
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun canonicalTripleRow(t: RdfTriple): String =
        fields(termKey(t.subject), termKey(t.predicate), termKey(t.obj))

    // Length framing makes delimiters, NULs and nested terms unambiguous.
    private fun fields(vararg values: String): String = values.joinToString("") { "${it.length}:$it" }

    private fun termKey(term: RdfTerm): String =
        when (term) {
            is Iri -> "I" + fields(term.value)
            is BlankNode -> "B" + fields(term.id)
            is TripleTerm ->
                "T" + canonicalTripleRow(term.triple)
            is LangString -> "L" + fields(term.lexical, term.lang, term.direction?.name ?: "", term.datatype.value)
            is TypedLiteral -> "D" + fields(term.lexical, term.datatype.value)
            is Literal -> "LIT" + fields(term.lexical, term.datatype.value)
            else -> fields(term.javaClass.name, term.toString())
        }

    fun compileCacheKey(digest: String, config: ValidationConfig): String =
        listOf(
            digest,
            config.profile.name,
            config.strictMode.toString(),
            config.allowTripleTermsInShapeParameters.toString(),
            config.imports.resolveOwlImports.toString(),
        ).joinToString("|")
}
