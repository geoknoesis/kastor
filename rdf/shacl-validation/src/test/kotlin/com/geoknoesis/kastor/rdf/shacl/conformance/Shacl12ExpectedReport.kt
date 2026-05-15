package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.jena.rdfTermFromJena
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Statement

/** Expected `sh:ValidationReport` from W3C `mf:result` (subset compared to native output). */
data class ExpectedConformanceReport(
    val conforms: Boolean,
    /** Sorted keys aligned with [validationViolationConformanceSortKey]. */
    val violationSortKeys: List<String>,
    /** When true, only [conforms] and violation count are asserted (focus/path mismatches across engines). */
    val skipDetailedComparison: Boolean,
    val skipReason: String?,
    /**
     * Severities that force `sh:conforms false` when present among validation results.
     * When null or empty, **any** validation row fails conformance (W3C default in the SHACL 1.2 suite).
     * When set (e.g. `{sh:Violation}`), results whose severity is **not** listed still allow `sh:conforms true`.
     */
    val conformanceDisallowsSeverityIrises: Set<String>? = null,
)

object Shacl12ExpectedReport {

    private const val SH = "http://www.w3.org/ns/shacl#"

    fun parse(model: Model, expectedReport: Resource): ExpectedConformanceReport {
        val conformsStmt =
            expectedReport.getProperty(model.createProperty(SH + "conforms"))
                ?: error("expected ValidationReport missing sh:conforms")
        val conforms = conformsStmt.boolean

        val resultProp = model.createProperty(SH + "result")
        val resultStmts = expectedReport.listProperties(resultProp).collectStatements()

        val keys = mutableListOf<String>()
        var skip = false
        var skipReason: String? = null

        fun noteSkip(reason: String) {
            if (!skip) {
                skip = true
                skipReason = reason
            }
        }

        for (rs in resultStmts) {
            val rn = rs.`object`
            if (!rn.isResource) continue
            val result = rn.asResource()

            val focusStmt = result.getProperty(model.createProperty(SH + "focusNode"))
            val focusRdf = focusStmt?.`object` ?: continue

            val term = rdfTermFromJena(focusRdf)
            if (term is BlankNode) {
                noteSkip("blank focus nodes are not mapped identically across engines")
            }
            val focusKey = w3cViolationFocusKey(term)

            val pathStmt = result.getProperty(model.createProperty(SH + "resultPath"))
            val pathKey = resultPathKey(model, pathStmt, ::noteSkip)

            val compStmt =
                result.getProperty(model.createProperty(SH + "sourceConstraintComponent"))
                    ?: error("expected result missing sh:sourceConstraintComponent")
            check(compStmt.`object`.isURIResource) {
                "sourceConstraintComponent must be an IRI in W3C manifests"
            }
            val componentIri = compStmt.`object`.asResource().uri

            val shapeStmt = result.getProperty(model.createProperty(SH + "sourceShape"))
            val shapeIri =
                shapeStmt?.takeIf { it.`object`.isURIResource }?.`object`?.asResource()?.uri ?: ""

            val sevStmt = result.getProperty(model.createProperty(SH + "resultSeverity"))
            val severityIri =
                sevStmt?.takeIf { it.`object`.isURIResource }?.`object`?.asResource()?.uri
                    ?: (SH + "Violation")

            keys.add(
                listOf(focusKey, pathKey, componentIri, shapeIri, severityIri).joinToString("\u0001"),
            )
        }

        val conformanceDisallowsSeverityIrises: Set<String>? = run {
            val out = mutableSetOf<String>()
            val it = expectedReport.listProperties(model.createProperty(SH + "conformanceDisallows"))
            while (it.hasNext()) {
                val o = it.nextStatement().`object`
                if (o.isURIResource) out.add(o.asResource().uri)
            }
            out.takeIf { it.isNotEmpty() }
        }

        keys.sort()
        return ExpectedConformanceReport(
            conforms = conforms,
            violationSortKeys = keys,
            skipDetailedComparison = skip,
            skipReason = skipReason,
            conformanceDisallowsSeverityIrises = conformanceDisallowsSeverityIrises,
        )
    }

    private fun resultPathKey(model: Model, pathStmt: Statement?, noteSkip: (String) -> Unit): String {
        if (pathStmt == null) return ""
        val obj = pathStmt.`object`
        when {
            obj.isURIResource -> return "P|" + obj.asResource().uri
            obj.isLiteral -> {
                noteSkip("literal sh:resultPath is not compared")
                return "COMPLEX"
            }
        }
        val head = obj.asResource()
        return try {
            val list = model.getList(head)
            val iris = mutableListOf<String>()
            for (node in list) {
                if (!node.isURIResource) {
                    noteSkip("non-IRI segment in sh:resultPath list")
                    return "COMPLEX"
                }
                iris.add(node.asResource().uri)
            }
            when (iris.size) {
                0 -> ""
                1 -> "P|" + iris[0]
                else -> "COMPLEX|" + iris.joinToString("|")
            }
        } catch (_: Exception) {
            noteSkip("non-simple sh:resultPath is not yet compared")
            "COMPLEX"
        }
    }
}
