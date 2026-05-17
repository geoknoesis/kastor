package com.geoknoesis.kastor.ontoquality.catalog

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QuerySolution
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF

class ResourceCatalog(
    override val id: String,
    override val name: String,
    override val version: String,
    private val resourcePath: String,
) : ShapeCatalog {

    private val parsedShapes: RdfGraph by lazy {
        val stream =
            ResourceCatalog::class.java.getResourceAsStream(resourcePath)
                ?: error("Bundled shape catalog not found on classpath: $resourcePath")
        stream.use { Rdf.parseFromInputStream(it, RdfFormat.TURTLE) }
    }

    override val shapeMetadata: Map<String, ShapeMetadata> by lazy {
        extractMetadataFromGraph(parsedShapes)
    }

    override fun loadShapesGraph(): RdfGraph = parsedShapes

    private fun extractMetadataFromGraph(g: RdfGraph): Map<String, ShapeMetadata> {
        val model = JenaBridge.toJenaModel(g)
        val queryString =
            when (id) {
                "data-quality" -> """
                    PREFIX sh: <http://www.w3.org/ns/shacl#>
                    PREFIX dqcsh: <http://semwebquality.org/ontologies/dq-constraints-shacl#>
                    SELECT ?shape ?cat WHERE {
                      ?shape a sh:ConstraintComponent .
                      ?shape dqcsh:category ?cat .
                    }
                """.trimIndent()
                "skos-validation" -> """
                    PREFIX skvsh: <http://example.org/skos-validation-shacl#>
                    SELECT ?shape ?cat ?code WHERE {
                      ?shape skvsh:category ?cat .
                      OPTIONAL { ?shape skvsh:rule ?code . }
                    }
                """.trimIndent()
                else -> """
                    PREFIX oqsh: <http://example.org/owl-quality-shacl#>
                    SELECT ?shape ?cat ?code ?tierIri WHERE {
                      ?shape oqsh:category ?cat .
                      OPTIONAL { ?shape oqsh:pitfall ?code . }
                      OPTIONAL { ?shape oqsh:tier ?tierIri . }
                    }
                """.trimIndent()
            }

        val query = QueryFactory.create(queryString)
        val out = mutableMapOf<String, ShapeMetadata>()
        QueryExecutionFactory.create(query, model).use { qexec ->
            val results = qexec.execSelect()
            while (results.hasNext()) {
                val sol = results.nextSolution()
                val shapeRes = sol.getResource("shape") ?: continue
                if (!shapeRes.isURIResource) continue
                val shapeUri = shapeRes.uri
                val catNode = sol.get("cat") ?: continue
                val category =
                    when {
                        catNode.isLiteral ->
                            parseCategoryFromEnumName(catNode.asLiteral().lexicalForm)
                        catNode.isURIResource -> mapCategoryIri(id, catNode.asResource().uri)
                        else -> QualityCategory.UNCATEGORIZED
                    }
                val codeStr = bindingLexical(sol, "code")
                val pitfall: PitfallReference? = codeStr?.let { parsePitfallCode(it, id) }
                val tier = mapQualityTier(bindingTierUri(sol, "tierIri"))
                out[shapeUri] =
                    ShapeMetadata(category = category, pitfall = pitfall, tier = tier)
            }
        }
        expandNestedShaclShapeMetadata(model, out)
        return out
    }

    /**
     * SHACL validators report violations on nested PropertyShape blank nodes, while catalogue
     * metadata (OOPS code, category) is attached to the parent NodeShape IRI. Copy metadata along
     * structural shape links so [QualityFinding] can resolve [ValidationViolation.shapeUri].
     */
    private fun expandNestedShaclShapeMetadata(model: Model, metadata: MutableMap<String, ShapeMetadata>) {
        if (metadata.isEmpty()) return
        val nil = RDF.nil
        fun resourceKey(r: Resource): String =
            if (r.isURIResource) {
                r.uri
            } else {
                BlankNode(r.id.toString()).toString()
            }
        fun rdfListMembers(head: Resource): List<Resource> {
            val acc = mutableListOf<Resource>()
            var cur: Resource? = head
            val path = mutableSetOf<Resource>()
            while (cur != null && cur != nil && path.add(cur)) {
                val first = cur.getProperty(RDF.first)?.`object`
                if (first != null && first.isResource) acc.add(first.asResource())
                val rest = cur.getProperty(RDF.rest)?.`object`
                cur =
                    when {
                        rest == null || !rest.isResource -> null
                        rest.asResource() == nil -> null
                        else -> rest.asResource()
                    }
            }
            return acc
        }
        fun structuralChildren(node: Resource): Sequence<Resource> =
            sequence {
                for (p in SHACLNestedShapeLink.nonListPredicates) {
                    val prop = model.getProperty(p)
                    val it = model.listObjectsOfProperty(node, prop)
                    while (it.hasNext()) {
                        val o = it.next()
                        if (o.isResource) yield(o.asResource())
                    }
                }
                for (p in SHACLNestedShapeLink.listPredicates) {
                    val prop = model.getProperty(p)
                    val it = model.listObjectsOfProperty(node, prop)
                    while (it.hasNext()) {
                        val o = it.next()
                        if (o.isResource) {
                            for (m in rdfListMembers(o.asResource())) {
                                yield(m)
                            }
                        }
                    }
                }
            }
        val queue = ArrayDeque<Pair<Resource, ShapeMetadata>>()
        val expanded = mutableSetOf<String>()
        for ((uri, sm) in metadata) {
            if (!uri.startsWith("_:")) queue.add(model.createResource(uri) to sm)
        }
        while (queue.isNotEmpty()) {
            val (node, inherited) = queue.removeFirst()
            val nk = resourceKey(node)
            if (!expanded.add(nk)) continue
            if (node.isAnon && metadata[nk] == null) metadata[nk] = inherited
            for (child in structuralChildren(node)) {
                val ck = resourceKey(child)
                if (child.isAnon && metadata[ck] == null) metadata[ck] = inherited
                queue.add(child to inherited)
            }
        }
    }

    private object SHACLNestedShapeLink {
        private const val NS = "http://www.w3.org/ns/shacl#"
        val nonListPredicates =
            listOf(
                "${NS}property",
                "${NS}node",
                "${NS}not",
                "${NS}sparql",
                "${NS}qualifiedValueShape",
            )
        val listPredicates = listOf("${NS}and", "${NS}or", "${NS}xone")
    }

    private fun bindingLexical(sol: QuerySolution, varName: String): String? {
        if (!sol.contains(varName)) return null
        val node = sol.get(varName) ?: return null
        return when {
            node.isLiteral -> node.asLiteral().lexicalForm
            else -> null
        }
    }

    private fun parseCategoryFromEnumName(raw: String): QualityCategory =
        try {
            QualityCategory.valueOf(raw.trim())
        } catch (_: IllegalArgumentException) {
            QualityCategory.UNCATEGORIZED
        }

    /** Map catalogue-specific category IRIs (SKOS / DQ) to [QualityCategory]. */
    private fun mapCategoryIri(catalogId: String, iri: String): QualityCategory {
        if (catalogId == "skos-validation") {
            return when (iri) {
                "http://example.org/skos-validation-shacl#ClassStructure" ->
                    QualityCategory.SKOS_CLASS_STRUCTURE
                "http://example.org/skos-validation-shacl#Labels" -> QualityCategory.SKOS_LABELS
                "http://example.org/skos-validation-shacl#SemanticRelations" ->
                    QualityCategory.SKOS_SEMANTIC_RELATIONS
                "http://example.org/skos-validation-shacl#ConceptSchemes" ->
                    QualityCategory.SKOS_CONCEPT_SCHEMES
                "http://example.org/skos-validation-shacl#Mapping" -> QualityCategory.SKOS_MAPPING
                "http://example.org/skos-validation-shacl#Collections" ->
                    QualityCategory.SKOS_COLLECTIONS
                "http://example.org/skos-validation-shacl#Notations" -> QualityCategory.SKOS_NOTATIONS
                else -> QualityCategory.UNCATEGORIZED
            }
        }
        if (catalogId == "data-quality") {
            return when (iri) {
                "http://semwebquality.org/ontologies/dq-constraints#SyntaxConstraints" ->
                    QualityCategory.DQ_INTEGRITY
                "http://semwebquality.org/ontologies/dq-constraints#UniquenessConstraints" ->
                    QualityCategory.DQ_UNIQUENESS
                "http://semwebquality.org/ontologies/dq-constraints#LegalValueConstraints" ->
                    QualityCategory.DQ_LISTED_VALUES
                "http://semwebquality.org/ontologies/dq-constraints#FunctionalDependencyConstraints" ->
                    QualityCategory.DQ_FUNCTIONAL_DEPENDENCY
                else -> QualityCategory.UNCATEGORIZED
            }
        }
        val oqshNs = "http://example.org/owl-quality-shacl#"
        if (
            catalogId == "owl-quality" ||
                catalogId == "embedding-quality" ||
                catalogId == "modern-engineering" ||
                catalogId == "rdf12-quality" ||
                catalogId == "oops-pitfall-registry"
        ) {
            return when (iri) {
                "${oqshNs}Metadata" -> QualityCategory.OWL_METADATA
                "${oqshNs}Annotations" -> QualityCategory.OWL_ANNOTATIONS
                "${oqshNs}PropertyDeclarations" -> QualityCategory.OWL_PROPERTY_DECLARATIONS
                "${oqshNs}Hierarchy" -> QualityCategory.OWL_HIERARCHY
                "${oqshNs}VocabularyIntegrity" -> QualityCategory.OWL_VOCABULARY_INTEGRITY
                "${oqshNs}Inverses" -> QualityCategory.OWL_INVERSES
                "${oqshNs}Deprecation" -> QualityCategory.OWL_DEPRECATION
                "${oqshNs}Naming" -> QualityCategory.OWL_NAMING
                "${oqshNs}Semantics" -> QualityCategory.OWL_SEMANTICS
                "${oqshNs}FairCompliance" -> QualityCategory.FAIR_COMPLIANCE
                "${oqshNs}UriHygiene" -> QualityCategory.URI_HYGIENE
                "${oqshNs}LlmConsumability" -> QualityCategory.LLM_CONSUMABILITY
                "${oqshNs}OwlAntiPatterns" -> QualityCategory.OWL_ANTI_PATTERNS
                "${oqshNs}Multilingual" -> QualityCategory.MULTILINGUAL
                "${oqshNs}Reuse" -> QualityCategory.VOCABULARY_REUSE
                "${oqshNs}Maintainability" -> QualityCategory.MAINTAINABILITY
                "${oqshNs}Rdf12Conformance" -> QualityCategory.RDF12_CONFORMANCE
                else -> QualityCategory.UNCATEGORIZED
            }
        }
        return QualityCategory.UNCATEGORIZED
    }

    private fun bindingTierUri(sol: QuerySolution, varName: String): String? {
        if (!sol.contains(varName)) return null
        val node = sol.get(varName) ?: return null
        return if (node.isURIResource) node.asResource().uri else null
    }

    private fun mapQualityTier(tierIri: String?): QualityTier =
        when (tierIri) {
            "http://example.org/owl-quality-shacl#tier_Semantic" -> QualityTier.SEMANTIC
            "http://example.org/owl-quality-shacl#tier_Reasoning" -> QualityTier.REASONING
            else -> QualityTier.STRUCTURAL
        }

    private fun parsePitfallCode(code: String, catalogId: String): PitfallReference? {
        val trimmed = code.trim()
        val lc = trimmed.lowercase()
        if (lc == "convention") return PitfallReference.Convention
        if (catalogId == "data-quality") return null
        if (catalogId == "skos-validation") return PitfallReference.Skos(trimmed)
        val upper = trimmed.uppercase()
        if (Regex("^N\\d+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return PitfallReference.OntoQuality(upper)
        }
        if (Regex("^K\\d+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return PitfallReference.KastorExtension(upper)
        }
        if (Regex("^S\\d+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return PitfallReference.Skos(upper)
        }
        return PitfallReference.Oops(upper)
    }
}
