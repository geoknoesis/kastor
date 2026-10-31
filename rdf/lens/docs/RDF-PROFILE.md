# Semantic Lens RDF v0.2 implementation profile

Status: **experimental, strict subset** of the draft [Semantic Lens Ontology v0.2](https://example.org/semantic-lens/1.0). The vocabulary namespace is provisional and must not be treated as a W3C standard.

## Supported definitions

| RDF class | Required properties | Runtime result |
| --- | --- | --- |
| `lens:ClassLens` | one `lens:selectClass` IRI | node set |
| `lens:IntersectionLens` | one `lens:operands` RDF list | node set (AND) |
| `lens:UnionLens` | one `lens:operands` RDF list | node set (OR) |
| `lens:DifferenceLens` | one `lens:leftOperand`, one `lens:rightOperand` | scoped node-set difference |
| `lens:EgoNetworkLens` | `lens:seedLens`, `lens:predicate`, `lens:maxDepth` | GraphView |
| `lens:NetworkLens` | `lens:focusLens`, `lens:edgeLens` or `lens:predicate`, `lens:maxDepth`, `lens:closureMode`; optional `lens:nodeLens` | GraphView |

Network direction values are `lens:Outgoing`, `lens:Incoming` and `lens:Both`; closure values are `lens:Traversed` and `lens:Induced`. Depth is deliberately bounded to 0–3. Unknown / unsupported features are rejected.

### Example

```turtle
@prefix lens: <https://example.org/semantic-lens/1.0#> .
@prefix ex: <urn:lens:> .
ex:companies a lens:ClassLens ; lens:selectClass ex:Company .
ex:healthcare a lens:ClassLens ; lens:selectClass ex:Healthcare .
ex:filtered a lens:IntersectionLens ;
  lens:operands ( ex:companies ex:healthcare ) .
ex:excluded a lens:ClassLens ; lens:selectClass ex:Restricted .
ex:result a lens:DifferenceLens ;
  lens:leftOperand ex:filtered ;
  lens:rightOperand ex:excluded .

ex:start a lens:ClassLens ; lens:selectClass ex:StartingCompany .
ex:eligible a lens:ClassLens ; lens:selectClass ex:Company .
ex:edges a lens:EdgeLens ; lens:predicate ex:suppliesTo .
ex:network a lens:NetworkLens ;
  lens:focusLens ex:start ;
  lens:nodeLens ex:eligible ;
  lens:edgeLens ex:edges ;
  lens:maxDepth 2 ;
  lens:direction lens:Outgoing ;
  lens:closureMode lens:Traversed .
```

```kotlin
val definitions = Rdf.parse(turtle, "TURTLE")
val executor = KastorRdfLensExecutor(repository, definitions)
val nodes = executor.execute(Iri("urn:lens:result"))
val graph = executor.execute(Iri("urn:lens:network"))
```

Each operation runs through Kastor's `withSelectRows` with bounded result consumption. Network node and edge queries share one `readTransaction`.

## Safety and validation

The RDF reader enforces structural cardinality, absolute IRI requirements through Kastor terms, supported concrete type uniqueness, acyclic RDF lists, finite operands, recursion bounds, and strict integer depth. It **does not execute OWL imports**, dynamically load remote resources, or interpret arbitrary SPARQL snippets.

The reader does not yet provide full SHACL 1.2 validation; these are explicit, fail-closed structural checks for its supported vocabulary profile. The separate ontology-v0.2 SHACL shapes remain the normative structural baseline. `lens:FilterLens`, full typed comparisons, complex node/edge Boolean expressions, mapping/lifting, graph materialization and unrelated RDF lens types are **not** supported, and must not be silently ignored.

The library is not an authorization layer: the caller must supply an authorized dataset/repository (and scoped named graph where appropriate). When converting third-party RDF definitions to executable queries, validate source provenance and trust policy before calling the engine.

### Conformance tests
`LensRdfLoaderProviderTest` executes ontology-derived expressions on both real Kastor Jena and RDF4J repositories; it also checks duplicate property values, unsupported classes, recursive definitions, malformed/cyclic RDF lists, invalid depth and unsupported conditions. 
