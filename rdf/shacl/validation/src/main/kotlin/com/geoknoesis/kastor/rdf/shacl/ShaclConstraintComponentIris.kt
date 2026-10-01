package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.SHACL

private fun shCc(local: String): Iri = Iri(SHACL.namespace + local)

/**
 * `sh:sourceConstraintComponent` IRIs for portable violations. Every IRI returned in the `sh:` namespace is a term of
 * the SHACL 1.2 vocabulary; constraint types without a standard component map to `null`.
 */
internal fun ConstraintType.toSourceConstraintComponentIri(): Iri? =
    when (this) {
        ConstraintType.MIN_COUNT -> shCc("MinCountConstraintComponent")
        ConstraintType.MAX_COUNT -> shCc("MaxCountConstraintComponent")
        ConstraintType.DATATYPE -> shCc("DatatypeConstraintComponent")
        ConstraintType.CLASS -> shCc("ClassConstraintComponent")
        ConstraintType.NODE_KIND -> shCc("NodeKindConstraintComponent")
        ConstraintType.PATTERN -> shCc("PatternConstraintComponent")
        ConstraintType.MIN_LENGTH -> shCc("MinLengthConstraintComponent")
        ConstraintType.MAX_LENGTH -> shCc("MaxLengthConstraintComponent")
        ConstraintType.IN -> shCc("InConstraintComponent")
        ConstraintType.HAS_VALUE -> shCc("HasValueConstraintComponent")
        ConstraintType.LANGUAGE_IN -> shCc("LanguageInConstraintComponent")
        ConstraintType.UNIQUE_LANG -> shCc("UniqueLangConstraintComponent")
        ConstraintType.EQUALS -> shCc("EqualsConstraintComponent")
        ConstraintType.DISJOINT -> shCc("DisjointConstraintComponent")
        ConstraintType.LESS_THAN -> shCc("LessThanConstraintComponent")
        ConstraintType.LESS_THAN_OR_EQUALS -> shCc("LessThanOrEqualsConstraintComponent")
        ConstraintType.MIN_INCLUSIVE -> shCc("MinInclusiveConstraintComponent")
        ConstraintType.MAX_INCLUSIVE -> shCc("MaxInclusiveConstraintComponent")
        ConstraintType.MIN_EXCLUSIVE -> shCc("MinExclusiveConstraintComponent")
        ConstraintType.MAX_EXCLUSIVE -> shCc("MaxExclusiveConstraintComponent")
        ConstraintType.NODE -> shCc("NodeConstraintComponent")
        ConstraintType.NODE_BY_EXPRESSION -> shCc("NodeByExpressionConstraintComponent")
        ConstraintType.NOT -> shCc("NotConstraintComponent")
        ConstraintType.AND -> shCc("AndConstraintComponent")
        ConstraintType.OR -> shCc("OrConstraintComponent")
        ConstraintType.XONE -> shCc("XoneConstraintComponent")
        ConstraintType.SPARQL_CONSTRAINT,
        ConstraintType.SPARQL_CONSTRAINT_COMPONENT,
        -> SHACL.SPARQLConstraintComponent
        ConstraintType.QUALIFIED_VALUE_SHAPE -> shCc("QualifiedValueShapeConstraintComponent")
        ConstraintType.QUALIFIED_MIN_COUNT -> shCc("QualifiedMinCountConstraintComponent")
        ConstraintType.QUALIFIED_MAX_COUNT -> shCc("QualifiedMaxCountConstraintComponent")
        ConstraintType.CLOSED -> shCc("ClosedConstraintComponent")
        ConstraintType.MIN_LIST_LENGTH -> shCc("MinListLengthConstraintComponent")
        ConstraintType.MAX_LIST_LENGTH -> shCc("MaxListLengthConstraintComponent")
        ConstraintType.MEMBER_SHAPE -> shCc("MemberShapeConstraintComponent")
        ConstraintType.UNIQUE_MEMBERS -> shCc("UniqueMembersConstraintComponent")
        ConstraintType.SUBSET_OF -> shCc("SubsetOfConstraintComponent")
        ConstraintType.SINGLE_LINE -> shCc("SingleLineConstraintComponent")
        ConstraintType.SOME_VALUE -> shCc("SomeValueConstraintComponent")
        ConstraintType.ROOT_CLASS -> shCc("RootClassConstraintComponent")
        ConstraintType.UNIQUE_VALUES_FOR -> shCc("UniqueValuesForConstraintComponent")
        ConstraintType.SHAPE -> shCc("ShapeConstraintComponent")
        // SHACL 1.2 has a single component for reifiers: sh:reificationRequired is an (optional) parameter of
        // sh:ReifierShapeConstraintComponent (W3C core/property/reifierShape-002). There is no
        // sh:ReificationRequiredConstraintComponent, and no IRI is ever minted in the sh: namespace.
        ConstraintType.REIFIER_SHAPE,
        ConstraintType.REIFICATION_REQUIRED,
        -> shCc("ReifierShapeConstraintComponent")
        ConstraintType.PROPERTY_SHAPE,
        ConstraintType.FLAGS,
        ConstraintType.JS_CONSTRAINT,
        ConstraintType.PY_CONSTRAINT,
        ConstraintType.CUSTOM_CONSTRAINT,
        -> null
    }
