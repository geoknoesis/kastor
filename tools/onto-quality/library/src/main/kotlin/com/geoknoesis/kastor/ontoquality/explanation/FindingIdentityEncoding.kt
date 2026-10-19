package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import java.security.MessageDigest

/** Versioned finding identity. Field frames and RDF kind tags are independent of display syntax. */
internal object FindingIdentityEncoding {
    fun digest(finding: QualityFinding): String {
        val v = finding.violation
        val pathNodes = v.resultPathTriples.flatMapTo(HashSet<BlankNode>()) { triple ->
            listOfNotNull(triple.subject as? BlankNode, triple.obj as? BlankNode)
        }
        val pathKeys = if (pathNodes.isEmpty()) finding.blankNodeKeys else
            BlankNodeKeys.compute(MemoryGraph(v.resultPathTriples), pathNodes, prefix = "_:p")
        val sink = Sink(finding.blankNodeKeys)
        sink.field("kastor.finding.v2")
        sink.field(v.severity.name)
        sink.field(v.constraint.constraintType.name)
        sink.field(finding.stableMessage)
        sink.field(v.shapeUri?.let { if (it.startsWith("_:")) "_:" else it })
        sink.field(v.violationCode)
        sink.field(v.resultSeverityIri)
        sink.field(finding.category.name)
        sink.field(finding.tier.name)
        sink.field(when (val p = finding.pitfall) {
            null -> null
            is PitfallReference.Oops -> "OOPS:${p.number}"
            is PitfallReference.Skos -> "SKOS:${p.number}"
            is PitfallReference.OntoQuality -> "OQ:${p.number}"
            is PitfallReference.KastorExtension -> "KASTOR:${p.code}"
            PitfallReference.Convention -> "CONVENTION"
        })
        sink.term(v.focusNode)
        sink.int(v.path?.size ?: -1)
        v.path?.forEach { sink.term(it, anonymousShapes = true, termKeys = pathKeys) }
        sink.term(v.resultPathNode, anonymousShapes = true, termKeys = pathKeys)
        sink.term(v.sourceConstraint, anonymousShapes = true)
        sink.term(v.value)
        sink.term(v.context[ValidationViolation.REIFIER_CONTEXT_KEY] as? RdfTerm)
        return sink.finish()
    }

    private class Sink(private val keys: Map<BlankNode, String>) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(2048)
        private var position = 0
        private fun byte(value: Int) {
            if (position == buffer.size) flush()
            buffer[position++] = value.toByte()
        }
        private fun flush() { hash.update(buffer, 0, position); position = 0 }
        fun int(value: Int) { byte(value ushr 24); byte(value ushr 16); byte(value ushr 8); byte(value) }
        fun field(value: String?) {
            int(value?.length ?: -1)
            value?.forEach { byte(it.code ushr 8); byte(it.code) }
        }
        fun term(value: RdfTerm?, anonymousShapes: Boolean = false, depth: Int = 0,
                 termKeys: Map<BlankNode, String> = keys) {
            require(depth <= 128) { "Finding triple term nesting exceeds 128 levels" }
            when (value) {
                null -> byte(0)
                is Iri -> { byte(1); field(value.value) }
                is BlankNode -> { byte(2); field(termKeys[value] ?: if (anonymousShapes) "_:" else value.id) }
                is LangString -> {
                    byte(3); field(value.lexical); field(value.datatype.value)
                    field(value.normalizedLang); field(value.direction?.token)
                }
                is Literal -> { byte(4); field(value.lexical); field(value.datatype.value) }
                is TripleTerm -> {
                    byte(5)
                    term(value.triple.subject, anonymousShapes, depth + 1, termKeys)
                    term(value.triple.predicate, anonymousShapes, depth + 1, termKeys)
                    term(value.triple.obj, anonymousShapes, depth + 1, termKeys)
                }
                else -> { byte(6); field(value.javaClass.name); field(value.toString()) }
            }
        }
        fun finish(): String {
            flush()
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
