package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.util.EnumMap
import java.util.EnumSet

private fun shCc(local: String): Iri = Iri(SHACL.namespace + local)

/** The constraint types without a standard component, whose `sh:sourceConstraintComponent` is `null`. */
internal val constraintTypesWithoutSourceComponent: Set<ConstraintType> = EnumSet.of(
    ConstraintType.PROPERTY_SHAPE,
    ConstraintType.FLAGS,
    ConstraintType.JS_CONSTRAINT,
    ConstraintType.PY_CONSTRAINT,
    ConstraintType.CUSTOM_CONSTRAINT,
)

/** The standard component of every other constraint type (a test checks that the two cover [ConstraintType]). */
private val sourceConstraintComponents: Map<ConstraintType, Iri> = EnumMap(
    mapOf(
        ConstraintType.MIN_COUNT to shCc("MinCountConstraintComponent"),
        ConstraintType.MAX_COUNT to shCc("MaxCountConstraintComponent"),
        ConstraintType.DATATYPE to shCc("DatatypeConstraintComponent"),
        ConstraintType.CLASS to shCc("ClassConstraintComponent"),
        ConstraintType.NODE_KIND to shCc("NodeKindConstraintComponent"),
        ConstraintType.PATTERN to shCc("PatternConstraintComponent"),
        ConstraintType.MIN_LENGTH to shCc("MinLengthConstraintComponent"),
        ConstraintType.MAX_LENGTH to shCc("MaxLengthConstraintComponent"),
        ConstraintType.IN to shCc("InConstraintComponent"),
        ConstraintType.HAS_VALUE to shCc("HasValueConstraintComponent"),
        ConstraintType.LANGUAGE_IN to shCc("LanguageInConstraintComponent"),
        ConstraintType.UNIQUE_LANG to shCc("UniqueLangConstraintComponent"),
        ConstraintType.EQUALS to shCc("EqualsConstraintComponent"),
        ConstraintType.DISJOINT to shCc("DisjointConstraintComponent"),
        ConstraintType.LESS_THAN to shCc("LessThanConstraintComponent"),
        ConstraintType.LESS_THAN_OR_EQUALS to shCc("LessThanOrEqualsConstraintComponent"),
        ConstraintType.MIN_INCLUSIVE to shCc("MinInclusiveConstraintComponent"),
        ConstraintType.MAX_INCLUSIVE to shCc("MaxInclusiveConstraintComponent"),
        ConstraintType.MIN_EXCLUSIVE to shCc("MinExclusiveConstraintComponent"),
        ConstraintType.MAX_EXCLUSIVE to shCc("MaxExclusiveConstraintComponent"),
        ConstraintType.NODE to shCc("NodeConstraintComponent"),
        ConstraintType.NODE_BY_EXPRESSION to shCc("NodeByExpressionConstraintComponent"),
        ConstraintType.EXPRESSION to shCc("ExpressionConstraintComponent"),
        ConstraintType.NOT to shCc("NotConstraintComponent"),
        ConstraintType.AND to shCc("AndConstraintComponent"),
        ConstraintType.OR to shCc("OrConstraintComponent"),
        ConstraintType.XONE to shCc("XoneConstraintComponent"),
        ConstraintType.SPARQL_CONSTRAINT to SHACL.SPARQLConstraintComponent,
        ConstraintType.SPARQL_CONSTRAINT_COMPONENT to SHACL.SPARQLConstraintComponent,
        ConstraintType.QUALIFIED_VALUE_SHAPE to shCc("QualifiedValueShapeConstraintComponent"),
        ConstraintType.QUALIFIED_MIN_COUNT to shCc("QualifiedMinCountConstraintComponent"),
        ConstraintType.QUALIFIED_MAX_COUNT to shCc("QualifiedMaxCountConstraintComponent"),
        ConstraintType.CLOSED to shCc("ClosedConstraintComponent"),
        ConstraintType.MIN_LIST_LENGTH to shCc("MinListLengthConstraintComponent"),
        ConstraintType.MAX_LIST_LENGTH to shCc("MaxListLengthConstraintComponent"),
        ConstraintType.MEMBER_SHAPE to shCc("MemberShapeConstraintComponent"),
        ConstraintType.UNIQUE_MEMBERS to shCc("UniqueMembersConstraintComponent"),
        ConstraintType.SUBSET_OF to shCc("SubsetOfConstraintComponent"),
        ConstraintType.SINGLE_LINE to shCc("SingleLineConstraintComponent"),
        ConstraintType.SOME_VALUE to shCc("SomeValueConstraintComponent"),
        ConstraintType.ROOT_CLASS to shCc("RootClassConstraintComponent"),
        ConstraintType.UNIQUE_VALUES_FOR to shCc("UniqueValuesForConstraintComponent"),
        ConstraintType.SHAPE to shCc("ShapeConstraintComponent"),
        // SHACL 1.2 has a single component for reifiers: sh:reificationRequired is an (optional) parameter of
        // sh:ReifierShapeConstraintComponent (W3C core/property/reifierShape-002). There is no
        // sh:ReificationRequiredConstraintComponent, and no IRI is ever minted in the sh: namespace.
        ConstraintType.REIFIER_SHAPE to shCc("ReifierShapeConstraintComponent"),
        ConstraintType.REIFICATION_REQUIRED to shCc("ReifierShapeConstraintComponent"),
    ),
)

/**
 * `sh:sourceConstraintComponent` IRIs for portable violations. Every IRI returned in the `sh:` namespace is a term of
 * the SHACL 1.2 vocabulary; constraint types without a standard component map to `null`.
 */
internal fun ConstraintType.toSourceConstraintComponentIri(): Iri? = sourceConstraintComponents[this]
