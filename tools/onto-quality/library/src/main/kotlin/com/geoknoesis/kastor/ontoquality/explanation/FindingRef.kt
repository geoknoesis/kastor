package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityFinding

/**
 * Stable SHA-256 key for joining LLM explanations to a [QualityFinding].
 *
 * Encoding version 2 uses typed, length-framed UTF-16 fields. Keys from the earlier encoding must be
 * regenerated from findings; previously saved explanations cannot be joined using their old keys.
 *
 * The digest is derived from finding content (severity, message, shape, focus, paths, pitfall metadata,
 * constraint type, violation codes; a literal value with its datatype and language), **not** from row position in [com.geoknoesis.kastor.ontoquality.QualityReport.findings],
 * so importance-based reordering does not change refs.
 *
 * Blank nodes are keyed by [QualityFinding.blankNodeKeys] (a hash of the structure and the owner chain of the node,
 * unique within the asserted graph, set by [com.geoknoesis.kastor.ontoquality.QualityChecker]), so refs of findings on
 * e.g. `owl:Restriction` nodes do not change when the same file is parsed again or a reasoner is switched on, and two
 * blank nodes never share a ref. Blank-node references (`_:label`) that a message interpolates are replaced by the
 * same keys ([QualityFinding.stableMessage]). Without a key the parser label of a focus node or value is used, which
 * is stable only within one parse. Blank nodes of a result path belong to the shapes graph: they are keyed on that
 * graph, and without a key they are written as `_:` only, like anonymous shapes, whose blank-node label is left out
 * for the same reason.
 */
@JvmInline
value class FindingRef(val hexSha256: String) {
    companion object {
        fun from(finding: QualityFinding): FindingRef = FindingRef(FindingIdentityEncoding.digest(finding))

        /** Retained for API compatibility; refs are independent of report order. */
        fun from(finding: QualityFinding, @Suppress("UNUSED_PARAMETER") reportOrderIndex: Int): FindingRef = from(finding)
    }
}
