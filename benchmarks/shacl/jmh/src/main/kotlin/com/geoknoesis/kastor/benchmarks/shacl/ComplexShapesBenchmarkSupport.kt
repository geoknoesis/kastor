package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph

/**
 * Synthetic workloads exercising the parts of the native SHACL engine that trivial shapes never reach:
 * recursive `sh:node`, inline (blank node) shapes, logical constraints, qualified value shapes with
 * `sh:qualifiedValueShapesDisjoint`, and complex property paths.
 */
object ComplexShapesBenchmarkSupport {

  private const val PREFIXES =
      """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix ex: <http://example.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      """

  /** Workload names accepted by [shapes]. */
  @JvmField
  val WORKLOADS = listOf("recursiveNode", "inlineShapes", "logical", "qualified", "complexPaths")

  /** People linked by `ex:knows` chains, `ex:parent` trees and typed digits. */
  @JvmStatic
  fun data(people: Int): RdfGraph {
    val ttl = StringBuilder(PREFIXES)
    for (i in 0 until people) {
      ttl.append("ex:p$i a ex:Person ; ex:name \"person $i\" ; ex:age ${i % 90} ; ex:email \"p$i@example.org\" ")
      if (i + 1 < people) ttl.append("; ex:knows ex:p${i + 1} ")
      if (i > 0) ttl.append("; ex:parent ex:p${(i - 1) / 2} ")
      ttl.append("; ex:digit ex:d${i}_0, ex:d${i}_1, ex:d${i}_2 .\n")
      ttl.append("ex:d${i}_0 a ex:Thumb . ex:d${i}_1 a ex:Finger . ex:d${i}_2 a ex:Finger, ex:Thumb .\n")
    }
    return Rdf.parse(ttl.toString(), RdfFormat.TURTLE)
  }

  @JvmStatic
  fun shapes(workload: String): RdfGraph {
    val body =
        when (workload) {
          "recursiveNode" ->
              """
              ex:PersonShape a sh:NodeShape ; sh:targetNode ex:p0 ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;
                sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
              """
          "inlineShapes" ->
              """
              ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:knows ; sh:node [ sh:property [ sh:path ex:name ; sh:minLength 3 ] ] ] ;
                sh:property [ sh:path ex:parent ; sh:node [ sh:property [ sh:path ex:age ; sh:minInclusive 0 ] ] ] .
              """
          "logical" ->
              """
              ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:or ( [ sh:property [ sh:path ex:email ; sh:pattern "@example\\.org${'$'}" ] ]
                        [ sh:property [ sh:path ex:phone ; sh:minCount 1 ] ] ) ;
                sh:xone ( [ sh:property [ sh:path ex:age ; sh:maxInclusive 17 ] ]
                          [ sh:property [ sh:path ex:age ; sh:minInclusive 18 ] ] ) ;
                sh:not [ sh:property [ sh:path ex:name ; sh:maxLength 2 ] ] .
              """
          "qualified" ->
              """
              ex:HandShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:digit ; sh:qualifiedValueShape [ sh:class ex:Thumb ] ;
                              sh:qualifiedMinCount 1 ; sh:qualifiedMaxCount 1 ; sh:qualifiedValueShapesDisjoint true ] ;
                sh:property [ sh:path ex:digit ; sh:qualifiedValueShape [ sh:class ex:Finger ] ;
                              sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] .
              """
          "complexPaths" ->
              """
              ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path [ sh:inversePath ex:parent ] ; sh:maxCount 2 ] ;
                sh:property [ sh:path [ sh:oneOrMorePath ex:parent ] ; sh:class ex:Person ] ;
                sh:property [ sh:path ( ex:knows [ sh:zeroOrOnePath ex:knows ] ) ; sh:nodeKind sh:IRI ] ;
                sh:property [ sh:path [ sh:alternativePath ( ex:name ex:email ) ] ; sh:minCount 1 ] .
              """
          else -> error("Unknown workload '$workload'; expected one of $WORKLOADS")
        }
    return Rdf.parse(PREFIXES + body, RdfFormat.TURTLE)
  }
}
