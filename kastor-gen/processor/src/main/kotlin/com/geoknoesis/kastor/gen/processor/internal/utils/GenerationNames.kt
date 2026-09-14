package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.squareup.kotlinpoet.FileSpec

/**
 * One Kotlin member of a generated type, shared by every generator so that interfaces, wrappers, data classes,
 * factories, writers and DSL builders agree on names and signatures.
 *
 * @property typing the property that decides the member name (`sh:name`) and Kotlin type (value type and the
 *   cardinality used for the signature). For inherited members this is the supertype's signature, narrowed only
 *   where Kotlin allows a covariant override (`T?` to `T`).
 * @property constraints the conjunction of every declaration of the path along the inheritance chain; used for
 *   validation, KDoc and validation annotations. It may be stricter than [typing] (e.g. `sh:maxCount 1` restated
 *   on a path a supertype exposes as a `List`).
 * @property declared whether the type's interface declares (or re-declares) the member
 * @property inherited whether a supertype declares the member, so the declaration is an `override`
 * @property primaryForPath whether this is the first member (by name) for its path; writers, validators and DSL
 *   setters handle each path once
 */
internal class EffectiveMember(
    val typing: ShaclProperty,
    val constraints: ShaclProperty,
    val declared: Boolean,
    val inherited: Boolean,
    val primaryForPath: Boolean,
) {
    val path: String get() = typing.path
    val name: String get() = NamingUtils.propertyName(typing)
}

/**
 * Model-wide naming decisions shared by every generator: which types exist, which shapes a type
 * inherits from, the effective (own + inherited) members, and collision detection so that
 * ambiguous ontologies fail with a diagnostic instead of silently overwriting files or emitting
 * redeclarations.
 */
public object GenerationNames {

    /** PascalCase Kotlin type identifier for arbitrary text (same rules as generated type names). */
    public fun typeIdentifier(raw: String): String = NamingUtils.toTypeIdentifier(raw)

    /** camelCase Kotlin member identifier (unescaped) for arbitrary text (same rules as generated property names). */
    public fun memberIdentifier(raw: String): String = NamingUtils.toMemberIdentifier(raw)

    /** Whether [name] is a Kotlin hard keyword (needs backticks). */
    public fun isKeyword(name: String): Boolean = NamingUtils.isKeyword(name)

    /** Kotlin type names of all shapes in [model]. */
    public fun knownTypes(model: OntologyModel): Set<String> =
        model.shapes.map { NamingUtils.domainName(it.targetClass, model.context) }.toSet()

    /**
     * Direct supertypes of each shape (keyed by target class): the shapes in [model] whose target class is
     * listed in [ShaclShape.parentClasses]. Parents without a shape are ignored.
     *
     * @throws InvalidConfigurationException for cyclic inheritance
     */
    public fun superTypes(model: OntologyModel): Map<String, List<ShaclShape>> {
        val byClass = model.shapes.associateBy { it.targetClass }
        val result = model.shapes.associate { shape ->
            shape.targetClass to shape.parentClasses.distinct().sorted()
                .filter { it != shape.targetClass }
                .mapNotNull { byClass[it] }
        }
        // Reject cycles (Kotlin forbids cyclic interface inheritance).
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done
        fun visit(cls: String, path: List<String>) {
            when (state[cls]) {
                2 -> return
                1 -> throw InvalidConfigurationException(
                    config = "shape inheritance",
                    reason = "cyclic rdfs:subClassOf/sh:node inheritance: ${(path.dropWhile { it != cls } + cls).joinToString(" -> ")}",
                )
            }
            state[cls] = 1
            result[cls].orEmpty().forEach { visit(it.targetClass, path + cls) }
            state[cls] = 2
        }
        result.keys.sorted().forEach { visit(it, emptyList()) }
        return result
    }

    /**
     * The members of [shape]'s generated type (own and inherited), one per inherited member name and at most one
     * per own path, sorted by path. Each returned property carries the member's name and signature; see
     * [EffectiveMember.typing].
     *
     * @throws InvalidConfigurationException when supertypes declare incompatible signatures for one path
     */
    public fun effectiveProperties(shape: ShaclShape, model: OntologyModel): List<ShaclProperty> =
        effectiveMembers(model, superTypes(model))[shape.targetClass].orEmpty().map { it.typing }

    /**
     * Effective members of every shape in [model], keyed by target class.
     *
     * Rules:
     * - Several property shapes of one node shape on the same path become one member named after the
     *   alphabetically first `sh:name`; their constraints are combined (reported through [warn]).
     * - A path a supertype already declares keeps the supertype's member name (a different `sh:name` is reported
     *   through [warn]) and a signature that is a valid Kotlin override: a single-valued member may become
     *   required, a `List` stays a `List`, and the value type stays the supertype's. Refinements that a signature
     *   cannot express remain in [EffectiveMember.constraints] and are enforced by validation.
     * - When two supertypes expose the same path under different names, the type has both members.
     *
     * @throws InvalidConfigurationException when supertypes declare one path as a list and as a single value, or
     *   with different value types
     */
    internal fun effectiveMembers(
        model: OntologyModel,
        supers: Map<String, List<ShaclShape>>,
        warn: (String) -> Unit = {},
    ): Map<String, List<EffectiveMember>> {
        val memo = HashMap<String, List<EffectiveMember>>()
        model.shapes.sortedBy { it.targetClass }.forEach { resolveMembers(it, supers, memo, warn, emptySet()) }
        return memo
    }

    private fun resolveMembers(
        shape: ShaclShape,
        supers: Map<String, List<ShaclShape>>,
        memo: MutableMap<String, List<EffectiveMember>>,
        warn: (String) -> Unit,
        visiting: Set<String>,
    ): List<EffectiveMember> {
        memo[shape.targetClass]?.let { return it }
        if (shape.targetClass in visiting) return emptyList() // cycles are rejected by superTypes()
        val parentMembers = supers[shape.targetClass].orEmpty()
            .map { resolveMembers(it, supers, memo, warn, visiting + shape.targetClass) }
        val own = shape.properties.groupBy { it.path }.mapValues { (path, declarations) -> mergeOwn(shape, path, declarations, warn) }
        val paths = (own.keys + parentMembers.flatten().map { it.path }).toSortedSet()

        val members = mutableListOf<EffectiveMember>()
        for (path in paths) {
            val ownDeclaration = own[path]
            val inheritedByName = sortedMapOf<String, MutableList<EffectiveMember>>()
            parentMembers.flatten().filter { it.path == path }.forEach { inheritedByName.getOrPut(it.name) { mutableListOf() } += it }
            val merged = (listOfNotNull(ownDeclaration) + inheritedByName.values.flatten().map { it.constraints }).reduce(::conjoin)

            if (inheritedByName.isEmpty()) {
                members += EffectiveMember(merged, merged, declared = true, inherited = false, primaryForPath = false)
                continue
            }
            if (ownDeclaration != null && NamingUtils.propertyName(ownDeclaration) !in inheritedByName.keys) {
                warn(
                    "shape <${shape.shapeIri}>: sh:name \"${ownDeclaration.name}\" of <$path> differs from the inherited member " +
                        inheritedByName.keys.joinToString { "'$it'" } + "; the inherited name is used"
                )
            }
            inheritedByName.values.forEach { parents ->
                val typing = signature(shape, path, merged, parents.map { it.typing }, warn)
                val key = signatureKey(typing)
                val declared = ownDeclaration != null || parents.any { signatureKey(it.typing) != key }
                members += EffectiveMember(typing, merged, declared, inherited = true, primaryForPath = false)
            }
        }
        val sorted = members.sortedWith(compareBy({ it.path }, { it.name }))
        val result = sorted.mapIndexed { i, m ->
            EffectiveMember(m.typing, m.constraints, m.declared, m.inherited, primaryForPath = i == 0 || sorted[i - 1].path != m.path)
        }
        memo[shape.targetClass] = result
        return result
    }

    /** Several property shapes for one path in one node shape: one member, constraints combined. */
    private fun mergeOwn(shape: ShaclShape, path: String, declarations: List<ShaclProperty>, warn: (String) -> Unit): ShaclProperty {
        if (declarations.size == 1) return declarations.single()
        val ordered = declarations.sortedWith(compareBy({ it.name }, { it.description }))
        warn(
            "shape <${shape.shapeIri}>: ${declarations.size} property shapes for <$path> (" +
                ordered.joinToString { "sh:name \"${it.name}\"" } + "); generating one member '" +
                NamingUtils.propertyName(ordered.first()) + "' with their combined constraints"
        )
        return ordered.reduce(::conjoin)
    }

    /** Signature of an inherited member: compatible with every supertype's signature for the same name. */
    private fun signature(
        shape: ShaclShape,
        path: String,
        merged: ShaclProperty,
        parents: List<ShaclProperty>,
        warn: (String) -> Unit,
    ): ShaclProperty {
        val listKinds = parents.map { Cardinality.isList(it) }.distinct()
        if (listKinds.size > 1) {
            throw InvalidConfigurationException(
                config = "shape inheritance",
                reason = "shape <${shape.shapeIri}>: supertypes declare <$path> both as a list and as a single value; " +
                    "a Kotlin member cannot override both (align their sh:maxCount)",
            )
        }
        val valueTypes = parents.map { valueKey(it) }.distinct()
        if (valueTypes.size > 1) {
            throw InvalidConfigurationException(
                config = "shape inheritance",
                reason = "shape <${shape.shapeIri}>: supertypes declare <$path> with different value types " +
                    valueTypes.joinToString { it.toString() } + "; align their sh:datatype / sh:class",
            )
        }
        val base = parents.first()
        if (valueKey(merged) != valueTypes.single() && (merged.datatype != base.datatype || merged.targetClass != base.targetClass)) {
            warn(
                "shape <${shape.shapeIri}>: <$path> restates the value type of an inherited member; the inherited type " +
                    "is kept and the restatement is only enforced by validation"
            )
        }
        val isList = listKinds.single()
        return base.copy(
            minCount = merged.minCount,
            maxCount = if (isList) merged.maxCount?.takeIf { it > 1 } else merged.maxCount,
            description = merged.description.ifBlank { base.description },
        )
    }

    private fun signatureKey(p: ShaclProperty): List<Any?> =
        listOf(valueKey(p), Cardinality.isList(p), Cardinality.isRequiredSingle(p))

    private fun valueKey(p: ShaclProperty): List<Any?> = when {
        p.enumName != null -> listOf("enum", p.enumName)
        p.targetClass != null -> listOf("class", p.targetClass)
        TypeMapper.isResourceReference(p) -> listOf("resource")
        TypeMapper.isIriReference(p) -> listOf("iri")
        else -> listOf("literal", TypeMapper.literalMapping(p.datatype).type.toString())
    }

    /** Conjunction of two declarations of one path; for non-orderable parameters [a]'s value wins. */
    private fun conjoin(a: ShaclProperty, b: ShaclProperty): ShaclProperty {
        fun hi(x: Int?, y: Int?): Int? = if (x == null) y else if (y == null) x else kotlin.comparisons.maxOf(x, y)
        fun lo(x: Int?, y: Int?): Int? = if (x == null) y else if (y == null) x else kotlin.comparisons.minOf(x, y)
        fun hi(x: java.math.BigDecimal?, y: java.math.BigDecimal?): java.math.BigDecimal? =
            if (x == null) y else if (y == null) x else if (x >= y) x else y
        fun lo(x: java.math.BigDecimal?, y: java.math.BigDecimal?): java.math.BigDecimal? =
            if (x == null) y else if (y == null) x else if (x <= y) x else y
        val inValues = when {
            a.inValues == null -> b.inValues
            b.inValues == null -> a.inValues
            else -> a.inValues.filter { it in b.inValues }
        }
        return a.copy(
            description = a.description.ifBlank { b.description },
            datatype = a.datatype ?: b.datatype,
            targetClass = a.targetClass ?: b.targetClass,
            minCount = hi(a.minCount, b.minCount),
            maxCount = lo(a.maxCount, b.maxCount),
            minLength = hi(a.minLength, b.minLength),
            maxLength = lo(a.maxLength, b.maxLength),
            pattern = a.pattern ?: b.pattern,
            patternFlags = if (a.pattern != null) a.patternFlags else b.patternFlags,
            minInclusive = hi(a.minInclusive, b.minInclusive),
            maxInclusive = lo(a.maxInclusive, b.maxInclusive),
            minExclusive = hi(a.minExclusive, b.minExclusive),
            maxExclusive = lo(a.maxExclusive, b.maxExclusive),
            inValues = inValues,
            inValuesTyped = (a.inValuesTyped ?: b.inValuesTyped)?.let { typed -> if (inValues == null) typed else typed.filter { it.value in inValues } },
            enumName = a.enumName ?: b.enumName,
            hasValue = a.hasValue ?: b.hasValue,
            nodeKind = a.nodeKind ?: b.nodeKind,
            qualifiedValueShape = a.qualifiedValueShape ?: b.qualifiedValueShape,
            qualifiedMinCount = a.qualifiedMinCount ?: b.qualifiedMinCount,
            qualifiedMaxCount = a.qualifiedMaxCount ?: b.qualifiedMaxCount,
            severity = a.severity ?: b.severity,
            message = a.message ?: b.message,
            // The conjunction is only switched off when every declaration is.
            deactivated = a.deactivated && b.deactivated,
        )
    }

    /**
     * Fails when two shapes map to the same Kotlin type name (compared case-insensitively, because the
     * generated file names must also be distinct on case-insensitive file systems), when a class has more
     * than one shape, when two members of a type map to the same Kotlin member name, when supertypes declare
     * incompatible signatures, or when an `sh:pattern` is not a valid regular expression.
     *
     * @throws InvalidConfigurationException listing the colliding IRIs
     */
    public fun checkCollisions(model: OntologyModel) {
        val problems = mutableListOf<String>()
        model.shapes.groupBy { it.targetClass }.filterValues { it.size > 1 }.toSortedMap().forEach { (cls, shapes) ->
            problems += "class <$cls> has ${shapes.size} node shapes (${shapes.map { "<${it.shapeIri}>" }.sorted().joinToString()}); merge them into one"
        }
        model.shapes.distinctBy { it.targetClass }
            .groupBy { NamingUtils.domainName(it.targetClass, model.context).lowercase() }
            .filterValues { it.size > 1 }
            .toSortedMap()
            .forEach { (_, shapes) ->
                problems += "type name '${NamingUtils.domainName(shapes.first().targetClass, model.context)}' is derived from " +
                    shapes.map { "<${it.targetClass}>" }.sorted().joinToString() +
                    " (names are compared case-insensitively); add distinct JSON-LD context terms for these classes"
            }
        if (problems.isEmpty()) {
            val members = effectiveMembers(model, superTypes(model))
            model.shapes.sortedBy { it.targetClass }.forEach { shape ->
                members[shape.targetClass].orEmpty()
                    .groupBy { it.name }
                    .filterValues { group -> group.map { it.path }.distinct().size > 1 }
                    .toSortedMap()
                    .forEach { (name, group) ->
                        problems += "shape <${shape.shapeIri}>: property name '$name' is derived from " +
                            group.map { "<${it.path}> (sh:name \"${it.typing.name}\")" }.distinct().sorted().joinToString() +
                            "; give them distinct sh:name values"
                    }
            }
            problems += ShaclPatterns.problems(model)
        }
        if (problems.isNotEmpty()) {
            throw InvalidConfigurationException(
                config = "generated names",
                reason = "name collisions:\n  " + problems.joinToString("\n  "),
            )
        }
    }

    /**
     * Fails when two generated files would share a path, compared case-insensitively (e.g. `FOO.kt` and
     * `Foo.kt`, or a data class without suffix and its interface).
     */
    public fun checkUniqueFiles(files: Collection<FileSpec>) {
        val clashes = files.groupBy { "${it.packageName}.${it.name}".lowercase() }.filterValues { it.size > 1 }
        if (clashes.isNotEmpty()) {
            throw InvalidConfigurationException(
                config = "generated files",
                reason = "generated files collide (case-insensitive): " +
                    clashes.values.joinToString("; ") { group -> group.joinToString(" / ") { "${it.packageName}.${it.name}" } },
            )
        }
    }
}
