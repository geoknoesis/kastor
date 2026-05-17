package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import java.security.MessageDigest

/**
 * Stable SHA-256 key for joining LLM explanations to a [QualityFinding] row for a given report ordering.
 */
@JvmInline
value class FindingRef(val hexSha256: String) {
    companion object {
        fun from(finding: QualityFinding, reportOrderIndex: Int): FindingRef {
            val canonical =
                buildString {
                    append(reportOrderIndex).append('\u001f')
                    append(finding.violation.severity.name).append('\u001f')
                    append(finding.violation.message).append('\u001f')
                    append(finding.violation.shapeUri ?: "").append('\u001f')
                    append(finding.category.name).append('\u001f')
                    append(finding.tier.name).append('\u001f')
                    append(pitfallKey(finding)).append('\u001f')
                    append(termKey(finding.violation.focusNode)).append('\u001f')
                    append(finding.violation.path?.joinToString("\u001e") { termKey(it) } ?: "").append('\u001f')
                    append(finding.violation.value?.let { termKey(it) } ?: "")
                }
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

        private val HEX = "0123456789abcdef".toCharArray()

        private fun pitfallKey(f: QualityFinding): String =
            when (val p = f.pitfall) {
                null -> ""
                is com.geoknoesis.kastor.ontoquality.PitfallReference.Oops -> "OOPS:${p.number}"
                is com.geoknoesis.kastor.ontoquality.PitfallReference.Skos -> "SKOS:${p.number}"
                is com.geoknoesis.kastor.ontoquality.PitfallReference.OntoQuality -> "OQ:${p.number}"
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
