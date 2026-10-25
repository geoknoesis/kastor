package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

/**
 * Bean Validation annotations for a generated member's SHACL cardinality, shared by the interface and data-class
 * generators so both name the same annotation classes.
 *
 * A single-valued member with `sh:minCount >= 1` gets `@NotNull`. A list member gets `@NotEmpty` for
 * `sh:minCount >= 1` and `@Size(min, max)` when either bound is declared.
 */
internal object ValidationAnnotationSpecs {

    /** The annotation package for [flavour], or null for [ValidationAnnotations.NONE]. */
    fun constraintsPackage(flavour: ValidationAnnotations): String? = when (flavour) {
        ValidationAnnotations.JAKARTA -> "jakarta.validation.constraints"
        ValidationAnnotations.JAVAX -> "javax.validation.constraints"
        ValidationAnnotations.NONE -> null
    }

    fun forCardinality(
        flavour: ValidationAnnotations,
        isList: Boolean,
        minCount: Int?,
        maxCount: Int?,
        target: AnnotationSpec.UseSiteTarget,
    ): List<AnnotationSpec> {
        val pkg = constraintsPackage(flavour) ?: return emptyList()
        fun annotation(simpleName: String) = AnnotationSpec.builder(ClassName(pkg, simpleName)).useSiteTarget(target)
        val required = minCount != null && minCount > 0
        if (!isList) return if (required) listOf(annotation("NotNull").build()) else emptyList()
        return buildList {
            if (required) add(annotation("NotEmpty").build())
            if (minCount != null || maxCount != null) {
                val size = annotation("Size")
                minCount?.let { size.addMember("min = %L", it) }
                maxCount?.let { size.addMember("max = %L", it) }
                add(size.build())
            }
        }
    }
}
