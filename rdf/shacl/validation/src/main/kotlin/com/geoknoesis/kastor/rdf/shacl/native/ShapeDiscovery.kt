package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.shExpression

/** The node shapes of a shapes graph, and the resources of it that are shapes. */
internal object ShapeDiscovery {
    internal fun findNodeShapes(index: ShapeGraphIndex, budget: ValidationBudget): List<RdfResource> {
        val triples = index.triples
        val set = LinkedHashSet<RdfResource>()
        for (t in triples) {
            budget.tick("shape discovery")
            if (t.predicate == RDF.type &&
                (t.obj == SHACL.NodeShape || t.obj == SHACL.ShapeClass || t.obj == SHACL.Shape)
            ) {
                set.add(t.subject)
            }
        }
        // Implicit node shapes: subjects with targets (explicit, or an implicit class target because the subject is
        // a SHACL instance of rdfs:Class) and shape parameters but no `rdf:type` (W3C misc/severity-002, etc.).
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isNotEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            if (looksLikeImplicitNodeShape(s, index)) set.add(s)
        }
        // Standalone shapes declared with `sh:path` plus targets (W3C path-* manifests); covers PropertyShape
        // roots whether or not an explicit `rdf:type` triple is present.
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            set.add(s)
        }
        budget.check("shape discovery")
        return set.toList()
    }

    private fun hasShapeTargets(s: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(s, SHACL.targetClass).isNotEmpty() ||
            index.objects(s, SHACL.targetNode).isNotEmpty() ||
            index.objects(s, SHACL.targetSubjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetObjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetWhere).isNotEmpty() ||
            (s is Iri && index.isInstanceOf(s, RDFS.Class))

    private val shapeParameters: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`and`, SHACL.`or`, SHACL.xone, SHACL.`not`, SHACL.closed,
            SHACL.ignoredProperties, SHACL.datatype, SHACL.nodeByExpression, SHACL.nodeKind, SHACL.`class`,
            SHACL.pattern, SHACL.minCount, SHACL.maxCount, SHACL.uniqueValuesFor, SHACL.severity, SHACL.targetWhere,
            SHACL.`in`, SHACL.hasValue, SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive,
            SHACL.minLength, SHACL.maxLength, SHACL.languageIn, SHACL.sparql, SHACL.equals, SHACL.disjoint,
            SHACL.shape, SHACL.rootClass, SHACL.singleLine, SHACL.minListLength, SHACL.maxListLength,
            SHACL.memberShape, SHACL.uniqueMembers, SHACL.someValue, shExpression,
        )
    }

    private fun looksLikeImplicitNodeShape(s: RdfResource, index: ShapeGraphIndex): Boolean =
        shapeParameters.any { index.objects(s, it).isNotEmpty() }

    private val shapeClasses: Set<RdfTerm> by lazy { setOf(SHACL.NodeShape, SHACL.PropertyShape, SHACL.Shape, SHACL.ShapeClass) }

    private val shapeMarkers: Set<Iri> by lazy {
        (shapeParameters + listOf(
            SHACL.path, SHACL.deactivated, SHACL.targetClass, SHACL.targetNode, SHACL.targetSubjectsOf, SHACL.targetObjectsOf,
        )).toSet()
    }

    /**
     * The resources of [index] that are shapes: instances of a shape class, or subjects of a shape parameter,
     * `sh:path`, `sh:deactivated` or a target. Only IRIs unless [blankNodes], as a computed `shnex:conformsToShape`
     * shape must be one (§4.5.3).
     */
    internal fun shapeCandidates(index: ShapeGraphIndex, blankNodes: Boolean): Set<RdfResource> {
        val out = LinkedHashSet<RdfResource>()
        for (t in index.triples) {
            index.tick("shape discovery")
            val subject = t.subject
            if (subject !is Iri && !(blankNodes && subject is BlankNode)) continue
            if (t.predicate in shapeMarkers || (t.predicate == RDF.type && t.obj in shapeClasses)) out.add(subject)
        }
        return out
    }
}
