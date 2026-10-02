package com.geoknoesis.kastor.rdf.reasoning

/**
 * Membership test for the part of the **RDFS closure of the empty graph** that rule engines materialise lazily.
 *
 * A reasoner reports as *inferred* only what follows from the data; triples that hold in every RDFS interpretation
 * (axiomatic triples and their consequences) are not inferences. The engines compute most of them for an empty
 * input, and that set can simply be subtracted. Some, however, are only materialised once the data *mentions* the
 * term they are about, although they do not depend on the data at all:
 *
 * | Triple | For `T` in | Rule (RDF 1.1 Semantics) |
 * |---|---|---|
 * | `T rdfs:subPropertyOf T` | RDF/RDFS properties | rdfs6 |
 * | `T rdf:type rdf:Property` | RDF/RDFS properties | axiomatic |
 * | `T rdfs:subClassOf T` | RDF/RDFS classes, recognised datatypes | rdfs10 |
 * | `T rdfs:subClassOf rdfs:Resource` | RDF/RDFS classes, recognised datatypes | rdfs8 |
 * | `T rdf:type rdfs:Class` | RDF/RDFS classes, recognised datatypes | axiomatic, rdfs9 |
 * | `T rdf:type rdfs:Resource` | RDF/RDFS properties and classes, recognised datatypes | rdfs4 |
 * | `T rdf:type rdfs:Datatype` | recognised datatypes | rdfs1 |
 * | `T rdfs:subClassOf rdfs:Literal` | recognised datatypes | rdfs13 |
 * | `T rdf:type rdfs:ContainerMembershipProperty` | container membership properties `rdf:_n` | axiomatic |
 * | `T rdfs:subPropertyOf rdfs:member` | container membership properties `rdf:_n` | rdfs12 |
 * | `T rdfs:domain rdfs:Resource`, `T rdfs:range rdfs:Resource` | container membership properties `rdf:_n` | axiomatic |
 * | `rdf:nil rdf:type rdf:List` | | axiomatic |
 * | `rdf:nil rdf:type rdfs:Resource` | | rdfs4 |
 *
 * (There are infinitely many `rdf:_n`, so no engine can enumerate their axioms for the empty graph; `rdf:nil` is the
 * only individual of the two vocabularies.)
 *
 * The *recognised datatypes* are the RDF-compatible XSD types (RDF 1.1 Concepts, section 5.1) and `rdf:langString`,
 * `rdf:dirLangString`, `rdf:HTML`, `rdf:XMLLiteral`, `rdf:JSON`. A typical trigger is a schema statement such as
 * `ex:age rdfs:range xsd:integer`, after which an engine emits `xsd:integer rdf:type rdfs:Class`.
 *
 * With `owl = true` the corresponding OWL trivia of rule-based OWL engines are included: `T owl:equivalentClass T`
 * and `T rdf:type owl:Class` for classes and datatypes, `T owl:equivalentProperty T` for properties.
 *
 * Everything else is **not** covered, in particular statements about `owl:` terms or between vocabulary terms that
 * follow from schema the user asserted (e.g. `owl:FunctionalProperty rdfs:subClassOf rdf:Property` derived from two
 * asserted `rdfs:subClassOf` statements): those are real inferences and must be reported.
 */
object RdfsAxioms {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    private const val XSD = "http://www.w3.org/2001/XMLSchema#"
    private const val OWL = "http://www.w3.org/2002/07/owl#"

    private const val TYPE = RDF + "type"
    private const val PROPERTY = RDF + "Property"
    private const val SUB_CLASS_OF = RDFS + "subClassOf"
    private const val SUB_PROPERTY_OF = RDFS + "subPropertyOf"
    private const val RESOURCE = RDFS + "Resource"
    private const val CLASS = RDFS + "Class"
    private const val LITERAL = RDFS + "Literal"
    private const val DATATYPE = RDFS + "Datatype"
    private const val DOMAIN = RDFS + "domain"
    private const val RANGE = RDFS + "range"
    private const val MEMBER = RDFS + "member"
    private const val MEMBERSHIP_PROPERTY_CLASS = RDFS + "ContainerMembershipProperty"
    private const val NIL = RDF + "nil"
    private const val LIST = RDF + "List"

    private val PROPERTIES: Set<String> = (
        listOf("type", "subject", "predicate", "object", "first", "rest", "value").map { RDF + it } +
            listOf("subClassOf", "subPropertyOf", "domain", "range", "label", "comment", "member", "seeAlso", "isDefinedBy").map { RDFS + it }
        ).toSet()

    private val CLASSES: Set<String> = (
        listOf("Property", "Bag", "Seq", "Alt", "List", "Statement").map { RDF + it } +
            listOf("Resource", "Class", "Literal", "Datatype", "Container", "ContainerMembershipProperty").map { RDFS + it }
        ).toSet()

    private val DATATYPES: Set<String> = (
        listOf(
            "string", "boolean", "decimal", "integer", "double", "float", "date", "time", "dateTime", "dateTimeStamp",
            "gYear", "gMonth", "gDay", "gYearMonth", "gMonthDay", "duration", "yearMonthDuration", "dayTimeDuration",
            "byte", "short", "int", "long", "unsignedByte", "unsignedShort", "unsignedInt", "unsignedLong",
            "positiveInteger", "nonNegativeInteger", "negativeInteger", "nonPositiveInteger",
            "hexBinary", "base64Binary", "anyURI", "language", "normalizedString", "token", "NMTOKEN", "Name", "NCName",
        ).map { XSD + it } +
            listOf("langString", "dirLangString", "HTML", "XMLLiteral", "JSON").map { RDF + it }
        ).toSet()

    private val MEMBERSHIP_PROPERTY = Regex(Regex.escape(RDF) + "_[1-9][0-9]*")

    /** True for the properties of the RDF and RDFS vocabularies, including the container membership properties `rdf:_n`. */
    private fun isProperty(iri: String): Boolean = iri in PROPERTIES || MEMBERSHIP_PROPERTY.matches(iri)

    /** True for the container membership properties `rdf:_1`, `rdf:_2`, ... */
    private fun isMembershipProperty(iri: String): Boolean = MEMBERSHIP_PROPERTY.matches(iri)

    /** True for the classes of the RDF and RDFS vocabularies. */
    private fun isClass(iri: String): Boolean = iri in CLASSES

    /** True for the recognised datatypes (see the class documentation). */
    private fun isRecognisedDatatype(iri: String): Boolean = iri in DATATYPES

    /**
     * True when the triple of the IRIs [subject], [predicate], [obj] is one of the lazily materialised members of the
     * RDFS closure of the empty graph listed in the class documentation (with [owl], also their OWL counterparts).
     */
    @JvmOverloads
    fun isEntailedByEmptyGraph(subject: String, predicate: String, obj: String, owl: Boolean = false): Boolean {
        val classLike = isClass(subject) || isRecognisedDatatype(subject)
        return when (predicate) {
            SUB_PROPERTY_OF -> (subject == obj && isProperty(subject)) || (obj == MEMBER && isMembershipProperty(subject))
            DOMAIN, RANGE -> obj == RESOURCE && isMembershipProperty(subject)
            SUB_CLASS_OF -> when (obj) {
                subject, RESOURCE -> classLike
                LITERAL -> isRecognisedDatatype(subject)
                else -> false
            }
            TYPE -> when (obj) {
                RESOURCE -> classLike || isProperty(subject) || subject == NIL
                LIST -> subject == NIL
                MEMBERSHIP_PROPERTY_CLASS -> isMembershipProperty(subject)
                CLASS -> classLike
                DATATYPE -> isRecognisedDatatype(subject)
                PROPERTY -> isProperty(subject)
                OWL + "Class" -> owl && classLike
                else -> false
            }
            OWL + "equivalentClass" -> owl && subject == obj && classLike
            OWL + "equivalentProperty" -> owl && subject == obj && isProperty(subject)
            else -> false
        }
    }
}
