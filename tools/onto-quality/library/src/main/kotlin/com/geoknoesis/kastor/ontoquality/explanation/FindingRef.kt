package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import java.security.MessageDigest

/**
 * Stable SHA-256 key for joining LLM explanations to a [QualityFinding].
 *
 * The digest is derived from finding content (severity, message, shape, focus, paths, pitfall metadata,
 * constraint type, violation codes), **not** from row position in [com.geoknoesis.kastor.ontoquality.QualityReport.findings],
 * so importance-based reordering does not change refs.
 */
@JvmInline
value class FindingRef(val hexSha256: String) {
    companion object {
        fun from(finding: QualityFinding): FindingRef {
            val canonical = canonicalPayload(finding)
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            val hex =
                buildString(digest.size * 2) {
                    for (b in digest) {
                        val i = b.toInt() and 0xff
                        append(HEX[i ushr 4])
                        append(HEX[i and 0x0f])
                    }
                }
            return FindingRef(hex)
        }

        /**
         * Retained for API compatibility; [reportOrderIndex] is ignored (refs are order-independent).
         */
        fun from(finding: QualityFinding, @Suppress("UNUSED_PARAMETER") reportOrderIndex: Int): FindingRef =
            from(finding)

        private val HEX = "0123456789abcdef".toCharArray()

        private fun canonicalPayload(finding: QualityFinding): String {
            val v = finding.violation
            return buildString {
                append(v.severity.name).append('\u001f')
                append(v.constraint.constraintType.name).append('\u001f')
                append(v.message).append('\u001f')
                append(v.shapeUri ?: "").append('\u001f')
                append(v.violationCode ?: "").append('\u001f')
                append(v.resultSeverityIri ?: "").append('\u001f')
                append(finding.category.name).append('\u001f')
                append(finding.tier.name).append('\u001f')
                append(pitfallKey(finding)).append('\u001f')
                append(termKey(v.focusNode)).append('\u001f')
                append(v.path?.joinToString("\u001e") { termKey(it) } ?: "").append('\u001f')
                append(v.value?.let { termKey(it) } ?: "")
            }
        }

        private fun pitfallKey(f: QualityFinding): String =
            when (val p = f.pitfall) {
                null -> ""
                is com.geoknoesis.kastor.ontoquality.PitfallReference.Oops -> "OOPS:${p.number}"
                is com.geoknoesis.kastor.ontoquality.PitfallReference.Skos -> "SKOS:${p.number}"
                is com.geoknoesis.kastor.ontoquality.PitfallReference.OntoQuality -> "OQ:${p.number}"
                is com.geoknoesis.kastor.ontoquality.PitfallReference.KastorExtension -> "KASTOR:${p.code}"
                com.geoknoesis.kastor.ontoquality.PitfallReference.Convention -> "CONVENTION"
            }

        private fun termKey(term: RdfTerm): String =
            when (term) {
                is Iri -> term.value
                is BlankNode -> "_:${term.id}"
                is Literal -> term.lexical
                else -> term.toString()
            }
    }
}
