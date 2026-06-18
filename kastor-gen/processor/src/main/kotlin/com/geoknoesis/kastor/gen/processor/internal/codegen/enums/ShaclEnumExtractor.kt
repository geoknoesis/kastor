package com.geoknoesis.kastor.gen.processor.internal.codegen.enums

import com.geoknoesis.kastor.gen.processor.api.model.EnumMember
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.google.devtools.ksp.processing.KSPLogger

private const val XSD = "http://www.w3.org/2001/XMLSchema#"

internal class ShaclEnumExtractor(private val logger: KSPLogger) {

    fun enrich(model: OntologyModel): OntologyModel {
        val context = model.context
        val classShapeIris = model.shapes.map { it.targetClass }.toSet()
        val byName = LinkedHashMap<String, EnumModel>()

        val newShapes = model.shapes.map { shape ->
            val newProps = shape.properties.map { prop ->
                resolve(prop, context, classShapeIris, byName)
            }
            shape.copy(properties = newProps)
        }
        return model.copy(shapes = newShapes, enums = byName.values.toList())
    }

    private fun resolve(
        prop: ShaclProperty,
        context: JsonLdContext,
        classShapeIris: Set<String>,
        byName: LinkedHashMap<String, EnumModel>,
    ): ShaclProperty {
        val members = prop.inValuesTyped?.takeIf { it.isNotEmpty() } ?: return prop

        val allIri = members.all { it.isIri }
        val allLit = members.none { it.isIri }
        val kind = when {
            allIri -> EnumMemberKind.IRI
            allLit -> EnumMemberKind.LITERAL
            else -> {
                logger.warn("sh:in on ${prop.path} mixes IRI and literal members; not generating an enum")
                return prop
            }
        }

        // Malformed: literal members combined with sh:class
        if (kind == EnumMemberKind.LITERAL && prop.targetClass != null) {
            logger.warn("sh:in on ${prop.path} has literal members but also sh:class; malformed, skipping enum")
            return prop
        }

        val name = resolveEnumName(prop, kind, context) ?: return prop

        // A class that has its own NodeShape is a real entity, not an enum
        if (prop.targetClass != null && prop.targetClass in classShapeIris) {
            logger.warn("${prop.targetClass} has its own shape; treating ${prop.path} as an object reference, not an enum")
            return prop
        }

        val enumMembers = members.map { member ->
            if (kind == EnumMemberKind.IRI)
                EnumMember(
                    constantName = NamingUtils.toEnumConstant(localName(member.value)),
                    iri = member.value,
                )
            else
                EnumMember(
                    constantName = NamingUtils.toEnumConstant(member.value),
                    code = member.value,
                    datatype = member.datatype?.takeIf { it != "${XSD}string" },
                )
        }

        val candidate = EnumModel(name = name, classIri = prop.targetClass, memberKind = kind, members = enumMembers)

        val existing = byName[name]
        if (existing != null) {
            if (existing.members.toSet() != enumMembers.toSet()) {
                logger.warn("enum name collision for '$name' with different members; leaving ${prop.path} as non-enum")
                return prop
            }
            // Same name + identical members (full identity) → reuse existing, tag the property
        } else {
            byName[name] = candidate
        }

        return prop.copy(enumName = name)
    }

    private fun resolveEnumName(prop: ShaclProperty, kind: EnumMemberKind, context: JsonLdContext): String? {
        // IRI kind with sh:class → use the class local name
        if (kind == EnumMemberKind.IRI && prop.targetClass != null) {
            return NamingUtils.extractInterfaceName(prop.targetClass)
        }
        // Either kind: JSON-LD type mapping whose @type is a non-xsd class IRI
        val ctxTypeIri = (context.propertyMappings[prop.name]?.type as? JsonLdType.Iri)?.iri?.value
        if (ctxTypeIri != null && !ctxTypeIri.startsWith(XSD)) {
            return NamingUtils.extractInterfaceName(ctxTypeIri)
        }
        return null
    }

    private fun localName(iri: String): String = iri.substringAfterLast('/').substringAfterLast('#')
}
