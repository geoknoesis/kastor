package com.geoknoesis.kastor.gen.processor.internal.parsers

import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger
import org.apache.jena.rdf.model.Literal
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import java.io.InputStream
import java.math.BigDecimal
import java.io.StringReader

/**
 * Parser for SHACL (Shapes Constraint Language) files.
 * Extracts NodeShapes and their property constraints for code generation.
 * Uses Apache Jena for proper RDF parsing.
 *
 * ## Error handling
 * - A Turtle syntax error is fatal: it is reported through [KSPLogger.error] and rethrown as
 *   [InvalidConfigurationException], so callers never mistake a broken file for an ontology without shapes.
 * - Constructs the generator cannot represent are skipped with a [KSPLogger.warn] naming the shape and
 *   property (complex property paths, untyped properties, literal-valued `sh:property`, shapes without a
 *   target class, ...); one bad shape never prevents the remaining shapes from being extracted.
 *
 * ## Supported beyond `sh:datatype` / `sh:class`
 * - Blank-node node shapes (a stable synthetic IRI `urn:kastor:shape:<targetClass>` is used).
 * - Implicit class targets (a shape that is also an `rdfs:Class`/`owl:Class`).
 * - `sh:node <Shape>` on a property: typed as the referenced shape's target class.
 * - `sh:nodeKind`-only properties (IRI kinds are exposed as IRI strings, `sh:Literal` as lexical strings).
 * - `sh:or`/`sh:xone` whose members agree on one `sh:class` or one `sh:datatype`.
 * - Inheritance: `sh:node` on a node shape and `rdfs:subClassOf` between target classes populate
 *   [ShaclShape.parentClasses].
 */
public class ShaclParser(private val logger: KSPLogger) {

    public companion object {
        private const val SHACL_NS = "http://www.w3.org/ns/shacl#"
        private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        private const val RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#"
        private const val XSD_NS = "http://www.w3.org/2001/XMLSchema#"
        private const val OWL_CLASS = "http://www.w3.org/2002/07/owl#Class"
    }

    /**
     * Parses a SHACL file and extracts NodeShapes.
     *
     * @param inputStream The SHACL file input stream
     * @return List of extracted SHACL shapes, sorted by target class
     * @throws InvalidConfigurationException if the content is not valid Turtle
     */
    public fun parseShacl(inputStream: InputStream): List<ShaclShape> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseShaclContent(content)
    }

    /**
     * Parses SHACL content from a string using Apache Jena.
     *
     * @param content The SHACL content as string
     * @return List of extracted SHACL shapes, sorted by target class
     * @throws InvalidConfigurationException if the content is not valid Turtle
     */
    public fun parseShaclContent(content: String): List<ShaclShape> {
        val model = ModelFactory.createDefaultModel()
        try {
            model.read(StringReader(content), null, "TURTLE")
        } catch (e: Exception) {
            logger.error("Invalid SHACL Turtle: ${e.message}")
            throw InvalidConfigurationException(config = "SHACL", reason = "invalid Turtle: ${e.message}", cause = e)
        }
        logger.info("Successfully parsed SHACL file with ${model.size()} triples")
        return Extraction(model).run()
    }

    private inner class Extraction(private val model: Model) {
        private fun p(local: String): Property = model.createProperty(SHACL_NS + local)

        private val targetClassP = p("targetClass")
        private val nodeP = p("node")

        fun run(): List<ShaclShape> {
            val nodeShapeClass = model.createResource(SHACL_NS + "NodeShape")
            val candidates = (model.listSubjectsWithProperty(RDF.type, nodeShapeClass).toList() +
                model.listSubjectsWithProperty(targetClassP).toList())
                .distinct()
            logger.info("Found ${candidates.size} NodeShapes")

            val shapes = mutableListOf<ShaclShape>()
            candidates.sortedBy { label(it) }.forEach { shapeResource ->
                try {
                    shapes += extractShape(shapeResource)
                } catch (e: Exception) {
                    logger.error("Failed to extract SHACL shape ${label(shapeResource)}: ${e.message}")
                    throw InvalidConfigurationException(
                        config = "SHACL",
                        reason = "failed to extract shape ${label(shapeResource)}: ${e.message}",
                        cause = e,
                    )
                }
            }
            return shapes.sortedWith(compareBy({ it.targetClass }, { it.shapeIri }))
        }

        private fun label(resource: Resource): String =
            if (resource.isURIResource) "<${resource.uri}>" else "_:${resource.id.labelString}"

        private fun targetClassesOf(shape: Resource): List<String> {
            val explicit = shape.listProperties(targetClassP).toList().mapNotNull { stmt ->
                val node = stmt.`object`
                if (node.isURIResource) node.asResource().uri
                else {
                    logger.warn("Shape ${label(shape)}: sh:targetClass ${node} is not an IRI; ignored")
                    null
                }
            }
            val implicit = if (shape.isURIResource &&
                (shape.hasProperty(RDF.type, RDFS.Class) || shape.hasProperty(RDF.type, model.createResource(OWL_CLASS)))
            ) listOf(shape.uri) else emptyList()
            return (explicit + implicit).distinct().sorted()
        }

        private fun extractShape(shapeResource: Resource): List<ShaclShape> {
            val targets = targetClassesOf(shapeResource)
            if (targets.isEmpty()) {
                logger.warn("Shape ${label(shapeResource)} has no sh:targetClass; no type is generated for it")
                return emptyList()
            }
            val properties = extractPropertiesFromShape(shapeResource)
            val parentsFromNode = shapeResource.listProperties(nodeP).toList()
                .mapNotNull { it.`object`.takeIf { n -> n.isResource }?.asResource() }
                .flatMap { targetClassesOf(it) }
            return targets.map { targetClass ->
                val shapeIri = if (shapeResource.isURIResource) shapeResource.uri else "urn:kastor:shape:$targetClass"
                val parentsFromSubclass = model.createResource(targetClass).listProperties(RDFS.subClassOf).toList()
                    .mapNotNull { it.`object`.takeIf { n -> n.isURIResource }?.asResource()?.uri }
                val parents = (parentsFromNode + parentsFromSubclass).filter { it != targetClass }.distinct().sorted()
                logger.info("Extracted shape: $shapeIri -> $targetClass with ${properties.size} properties")
                ShaclShape(
                    shapeIri = shapeIri, targetClass = targetClass, properties = properties, parentClasses = parents,
                    deactivated = deactivated(shapeResource, "Shape ${label(shapeResource)}"),
                )
            }
        }

        private fun single(resource: Resource, property: Property, what: String, context: String): RDFNode? {
            val values = resource.listProperties(property).toList().map { it.`object` }
            if (values.size > 1) {
                logger.warn("$context: multiple $what values ${values.map { it.toString() }.sorted()}; using the first in sorted order")
            }
            return values.minByOrNull { it.toString() }
        }

        private fun text(resource: Resource, property: Property): String? {
            val literals = resource.listProperties(property).toList().mapNotNull { it.`object`.takeIf { n -> n.isLiteral }?.asLiteral() }
            return (literals.firstOrNull { it.language.isNullOrEmpty() }
                ?: literals.firstOrNull { it.language.equals("en", ignoreCase = true) }
                ?: literals.minByOrNull { it.language })?.lexicalForm
        }

        private fun int(node: RDFNode?, what: String, context: String): Int? {
            if (node == null) return null
            val value = node.takeIf { it.isLiteral }?.asLiteral()?.lexicalForm?.trim()?.toIntOrNull()
            if (value == null) logger.warn("$context: $what value $node is not an integer; ignored")
            return value
        }

        /** Exact decimal value of a numeric bound (integers beyond the `Double` range keep every digit). */
        private fun number(node: RDFNode?, what: String, context: String): BigDecimal? {
            if (node == null) return null
            val value = node.takeIf { it.isLiteral }?.asLiteral()?.lexicalForm?.trim()?.let { lexical ->
                try {
                    BigDecimal(lexical)
                } catch (_: NumberFormatException) {
                    null
                }
            }
            if (value == null) logger.warn("$context: $what value $node is not numeric; not generated (SHACL validation still applies)")
            return value
        }

        /** `sh:deactivated`: a literal `true` (or `1`). */
        private fun deactivated(shape: Resource, context: String): Boolean =
            single(shape, p("deactivated"), "sh:deactivated", context)
                ?.takeIf { it.isLiteral }?.asLiteral()?.lexicalForm?.trim() in setOf("true", "1")

        private fun iri(node: RDFNode?): String? = node?.takeIf { it.isURIResource }?.asResource()?.uri

        private fun rdfList(head: RDFNode?): List<RDFNode> {
            val out = mutableListOf<RDFNode>()
            var current: Resource? = head?.takeIf { it.isResource }?.asResource()
            val seen = HashSet<Resource>()
            val first = model.createProperty(RDF_NS + "first")
            val rest = model.createProperty(RDF_NS + "rest")
            while (current != null && current.uri != RDF_NS + "nil" && seen.add(current)) {
                current.getProperty(first)?.let { out += it.`object` }
                current = current.getProperty(rest)?.`object`?.takeIf { it.isResource }?.asResource()
            }
            return out
        }

        private fun extractPropertiesFromShape(shapeResource: Resource): List<ShaclProperty> {
            val properties = mutableListOf<ShaclProperty>()
            val shapeLabel = "Shape ${label(shapeResource)}"

            shapeResource.listProperties(p("property")).toList().forEach { propertyStmt ->
                val node = propertyStmt.`object`
                if (!node.isResource) {
                    logger.warn("$shapeLabel: sh:property value $node is a literal, not a property shape; skipped")
                    return@forEach
                }
                extractProperty(node.asResource(), shapeLabel)?.let { properties += it }
            }

            // Deterministic order regardless of blank-node identity.
            return properties.sortedWith(compareBy({ it.path }, { it.name }))
        }

        private fun extractProperty(propertyShape: Resource, shapeLabel: String): ShaclProperty? {
            val pathNode = single(propertyShape, p("path"), "sh:path", shapeLabel)
            if (pathNode == null) {
                logger.warn("$shapeLabel: property shape ${label(propertyShape)} has no sh:path; skipped")
                return null
            }
            if (!pathNode.isURIResource) {
                logger.warn("$shapeLabel: property shape ${label(propertyShape)} uses a complex sh:path (inverse/sequence/alternative); skipped")
                return null
            }
            val path = pathNode.asResource().uri
            val context = "$shapeLabel, property <$path>"

            var datatype = iri(single(propertyShape, p("datatype"), "sh:datatype", context))
            var targetClass = iri(single(propertyShape, p("class"), "sh:class", context))
            var nodeKind = iri(single(propertyShape, p("nodeKind"), "sh:nodeKind", context))

            if (datatype == null && targetClass == null) {
                single(propertyShape, nodeP, "sh:node", context)?.takeIf { it.isResource }?.asResource()?.let { referenced ->
                    val classes = targetClassesOf(referenced)
                    if (classes.size == 1) {
                        targetClass = classes.single()
                    } else {
                        logger.warn("$context: sh:node ${label(referenced)} has no single sh:targetClass; exposed as an IRI")
                        if (nodeKind == null) nodeKind = SHACL_NS + "IRI"
                    }
                }
            }

            if (datatype == null && targetClass == null) {
                val members = listOf("or", "xone").flatMap { rdfList(propertyShape.getProperty(p(it))?.`object`) }
                    .mapNotNull { it.takeIf { n -> n.isResource }?.asResource() }
                if (members.isNotEmpty()) {
                    val classes = members.mapNotNull { iri(it.getProperty(p("class"))?.`object`) }.distinct()
                    val datatypes = members.mapNotNull { iri(it.getProperty(p("datatype"))?.`object`) }.distinct()
                    when {
                        classes.size == 1 && datatypes.isEmpty() -> targetClass = classes.single()
                        datatypes.size == 1 && classes.isEmpty() -> datatype = datatypes.single()
                        classes.isNotEmpty() && datatypes.isEmpty() -> {
                            logger.warn("$context: sh:or/sh:xone over several classes $classes; exposed as an IRI")
                            if (nodeKind == null) nodeKind = SHACL_NS + "IRI"
                        }
                        else -> logger.warn("$context: sh:or/sh:xone mixing datatypes $datatypes and classes $classes; exposed as a lexical string")
                    }
                    if (nodeKind == null && targetClass == null && datatype == null && classes.isEmpty()) nodeKind = SHACL_NS + "Literal"
                }
            }

            if (datatype == null && targetClass == null && nodeKind == null) {
                logger.warn("$context: no sh:datatype, sh:class, sh:node or sh:nodeKind; skipped")
                return null
            }

            val name = text(propertyShape, p("name")) ?: path.substringAfterLast('#').substringAfterLast('/')
            val description = text(propertyShape, p("description")) ?: ""

            // sh:hasValue may be an IRI (e.g. a combination-matrix pin like sbe:differentEvents) OR a literal.
            val hasValue = single(propertyShape, p("hasValue"), "sh:hasValue", context)?.let { node ->
                when {
                    node.isURIResource -> node.asResource().uri
                    node.isLiteral -> node.asLiteral().lexicalForm
                    else -> null
                }
            }

            val inValuesTyped = propertyShape.getProperty(p("in"))?.`object`?.let { head ->
                rdfList(head).mapNotNull { member ->
                    when {
                        member.isURIResource -> ShaclInValue(value = member.asResource().uri, isIri = true)
                        member.isLiteral -> {
                            val literal: Literal = member.asLiteral()
                            ShaclInValue(
                                value = literal.lexicalForm,
                                isIri = false,
                                datatype = literal.datatypeURI,
                                language = literal.language?.takeIf { it.isNotEmpty() },
                            )
                        }
                        else -> {
                            logger.warn("$context: blank node in sh:in ignored")
                            null
                        }
                    }
                }.takeIf { it.isNotEmpty() }
            }

            return ShaclProperty(
                path = path,
                name = name,
                description = description,
                datatype = datatype,
                targetClass = targetClass,
                minCount = int(single(propertyShape, p("minCount"), "sh:minCount", context), "sh:minCount", context),
                maxCount = int(single(propertyShape, p("maxCount"), "sh:maxCount", context), "sh:maxCount", context),
                minLength = int(single(propertyShape, p("minLength"), "sh:minLength", context), "sh:minLength", context),
                maxLength = int(single(propertyShape, p("maxLength"), "sh:maxLength", context), "sh:maxLength", context),
                pattern = single(propertyShape, p("pattern"), "sh:pattern", context)?.takeIf { it.isLiteral }?.asLiteral()?.lexicalForm,
                patternFlags = single(propertyShape, p("flags"), "sh:flags", context)?.takeIf { it.isLiteral }?.asLiteral()?.lexicalForm,
                minInclusive = number(single(propertyShape, p("minInclusive"), "sh:minInclusive", context), "sh:minInclusive", context),
                maxInclusive = number(single(propertyShape, p("maxInclusive"), "sh:maxInclusive", context), "sh:maxInclusive", context),
                minExclusive = number(single(propertyShape, p("minExclusive"), "sh:minExclusive", context), "sh:minExclusive", context),
                maxExclusive = number(single(propertyShape, p("maxExclusive"), "sh:maxExclusive", context), "sh:maxExclusive", context),
                inValues = inValuesTyped?.map { it.value },
                inValuesTyped = inValuesTyped,
                hasValue = hasValue,
                nodeKind = nodeKind,
                qualifiedValueShape = iri(single(propertyShape, p("qualifiedValueShape"), "sh:qualifiedValueShape", context)),
                qualifiedMinCount = int(single(propertyShape, p("qualifiedMinCount"), "sh:qualifiedMinCount", context), "sh:qualifiedMinCount", context),
                qualifiedMaxCount = int(single(propertyShape, p("qualifiedMaxCount"), "sh:qualifiedMaxCount", context), "sh:qualifiedMaxCount", context),
                severity = iri(single(propertyShape, p("severity"), "sh:severity", context)),
                message = text(propertyShape, p("message")),
                deactivated = deactivated(propertyShape, context),
            )
        }
    }
}
