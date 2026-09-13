package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.squareup.kotlinpoet.FileSpec

/**
 * Model-wide naming decisions shared by every generator: which types exist, which shapes a type
 * inherits from, the effective (own + inherited) property list, and collision detection so that
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

    /** Own properties plus those inherited from supertypes (own definitions win), sorted by path. */
    public fun effectiveProperties(shape: ShaclShape, model: OntologyModel): List<ShaclProperty> =
        effectiveProperties(shape, superTypes(model))

    internal fun effectiveProperties(shape: ShaclShape, supers: Map<String, List<ShaclShape>>): List<ShaclProperty> {
        val byPath = LinkedHashMap<String, ShaclProperty>()
        fun collect(s: ShaclShape, seen: Set<String>) {
            if (s.targetClass in seen) return
            supers[s.targetClass].orEmpty().forEach { collect(it, seen + s.targetClass) }
            s.properties.forEach { byPath[it.path] = it }
        }
        collect(shape, emptySet())
        return byPath.values.sortedBy { it.path }
    }

    /** Paths declared by any (transitive) supertype of [shape]. */
    internal fun inheritedPaths(shape: ShaclShape, supers: Map<String, List<ShaclShape>>): Set<String> =
        supers[shape.targetClass].orEmpty().flatMap { effectiveProperties(it, supers) }.map { it.path }.toSet()

    /**
     * Fails when two shapes map to the same Kotlin type name (compared case-insensitively, because the
     * generated file names must also be distinct on case-insensitive file systems), when a class has more
     * than one shape, or when two properties of one shape map to the same Kotlin member name.
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
        val supers = superTypes(model)
        model.shapes.sortedBy { it.targetClass }.forEach { shape ->
            val properties = effectiveProperties(shape, supers) +
                shape.properties.groupBy { it.path }.filterValues { it.size > 1 }.values.flatMap { it.drop(1) }
            properties.groupBy { NamingUtils.propertyName(it) }.filterValues { it.size > 1 }.toSortedMap().forEach { (name, props) ->
                problems += "shape <${shape.shapeIri}>: property name '$name' is derived from " +
                    props.map { "<${it.path}> (sh:name \"${it.name}\")" }.sorted().joinToString() +
                    "; give them distinct sh:name values"
            }
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
