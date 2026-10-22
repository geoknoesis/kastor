package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/** RDF-H 0.8 community-draft vocabulary. Administrative relations do not imply domain parthood. */
object RDFH : Vocabulary {
  override val namespace: String = "https://w3id.org/rdf-h#"
  override val prefix: String = "h"

  val Holon: Iri by lazy { term("Holon") }
  /** Legacy registration type; carries no completeness or closed-world guarantee. */
  val CompleteHolon: Iri by lazy { term("CompleteHolon") }
  val inHolon: Iri by lazy { term("inHolon") }
  val contentGraph: Iri by lazy { term("contentGraph") }
  val parentHolon: Iri by lazy { term("parentHolon") }
  val imports: Iri by lazy { term("imports") }
  val importsShapes: Iri by lazy { term("importsShapes") }
  val AssertedBaseTripleShape: Iri by lazy { term("AssertedBaseTripleShape") }
  val HolonIntegrityShape: Iri by lazy { term("HolonIntegrityShape") }
  val ReifierMembershipShape: Iri by lazy { term("ReifierMembershipShape") }
  val ContentGraphMappingShape: Iri by lazy { term("ContentGraphMappingShape") }
  val CompositionHierarchyShape: Iri by lazy { term("CompositionHierarchyShape") }
}
