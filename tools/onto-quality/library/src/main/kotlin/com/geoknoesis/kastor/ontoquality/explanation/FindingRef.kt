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
 *
 * Blank nodes are keyed by [QualityFinding.blankNodeKeys] (a structural hash of the node's description, set by
 * [com.geoknoesis.kastor.ontoquality.QualityChecker]), so refs of findings on e.g. `owl:Restriction` nodes do not
 * change when the same file is parsed again. Without a key the parser label is used, which is stable only within one
 * parse. Anonymous shapes are keyed without their blank-node label for the same reason.
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
                append(shapeKey(v.shapeUri)).append('\u001f')
                append(v.violationCode ?: "").append('\u001f')
                append(v.resultSeverityIri ?: "").append('\u001f')
                append(finding.category.name).append('\u001f')
                append(finding.tier.name).append('\u001f')
                append(pitfallKey(finding)).append('\u001f')
                append(termKey(v.focusNode, finding)).append('\u001f')
                append(v.path?.joinToString("\u001e") { termKey(it, finding) } ?: "").append('\u001f')
                append(v.value?.let { termKey(it, finding) } ?: "")
            }
        }

        /**
         * Anonymous (property) shapes are reported by their blank-node label, which changes whenever the shapes graph
         * is parsed; they are keyed as `_:` only (constraint type, path, message and severity identify the finding).
         */
        private fun shapeKey(shapeUri: String?): String =
            when {
                shapeUri == null -> ""
                shapeUri.startsWith("_:") -> "_:"
                else -> shapeUri
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

        private fun termKey(term: RdfTerm, finding: QualityFinding): String =
            when (term) {
                is Iri -> term.value
                is BlankNode -> finding.blankNodeKeys[term] ?: term.toString()
                is Literal -> term.lexical
                else -> term.toString()
            }
    }
}
