# `onto-quality-metrics`

Deterministic structural metrics for OWL ontologies and SKOS vocabularies
in the Kastor ecosystem.

## What it computes

This module implements the **OQuaRE** metric catalogue (Duque-Ramos et al.,
2014) plus Kastor-specific SKOS metrics, emitted under the Kastor metrics
vocabulary at `https://w3id.org/kastor/metrics#`.

### OQuaRE metrics (15)

Structural: depthOfInheritanceTree (DITOnto), numberOfAncestorClasses
(NACOnto), numberOfChildren (NOCOnto), couplingBetweenObjects (CBOOnto).

Complexity: weightedMethodCount (WMCOnto), responseForClass (RFCOnto),
numberOfProperties (NOMOnto), lackOfCohesionInMethods (LCOMOnto).

Richness: relationshipRichness (RROnto), inheritanceRichness (INROnto),
attributeRichness (AROnto), classRichness (CROnto), annotationRichness
(ANOnto), propertiesRichness (PROnto).

Other: tangledness (TMOnto).

Each metric is emitted with raw value, optional 1–5 score per the
Duque-Ramos 2014 scoring scheme, and SKOS provenance to the OQuaRE
concept. Metrics reported under an OQuaRE name always use the
**published** OQuaRE definition and bands, so their values are
comparable with other OQuaRE tools.

### Kastor-adapted variants (3, not OQuaRE)

`numberOfChildrenKastor` (NOCOntoKastor), `couplingBetweenObjectsKastor`
(CBOOntoKastor) and `tanglednessKastor` (TMOntoKastor) are reported
separately (`OwlMetricsSection.kastorAdapted`; JSON `owl.kastorAdapted`;
Turtle scores use `kastor-m:KastorAdaptedScoring`). They fix blind spots
of the published formulas but are **not** OQuaRE metrics and must not be
compared with OQuaRE scores.

### Formulas

Computed over the **asserted** graph after namespace exclusion. `C` =
named classes; `Root` = classes without a named (non-cycle) superclass;
`Sup_C` / `Sub_C` = direct named superclasses / subclasses;
`|SubClassOf|` = Σ|Sup_C|; `P_C` = declared object/datatype properties
*used* by C (`rdfs:domain C` or an `owl:Restriction` on C, each pair
counted once); `Assoc_C` = named classes associated with C through a
property (`rdfs:range` of a property whose `rdfs:domain` is C, or the
`owl:someValuesFrom` / `owl:allValuesFrom` / `owl:onClass` filler of a
restriction on C). Paths run from **owl:Thing** to a leaf over the acyclic
hierarchy: owl:Thing has depth 0, every root has depth 1, and an isolated
class (no superclass, no subclass) is one path of length 1. Cycle
participants are excluded and reported separately.

| Metric | Formula |
|--------|---------|
| DITOnto | longest owl:Thing-to-leaf path (edges); depth(Root) = 1 |
| NACOnto | Σ\|Sup_leaf\| / \|leaves\| |
| NOCOnto | Σ\|Sub_C\| / (\|C\| − \|Root\|) |
| CBOOnto | Σ\|Sup_C\| / (\|C\| − \|Root\|) |
| WMCOnto | Σ(\|P_C\| + \|Sub_C\|) / \|C\| |
| RFCOnto | Σ(\|P_C\| + \|Sup_C\|) / (\|C\| − \|Root\|) |
| NOMOnto | Σ\|P_C\| / \|C\| |
| LCOMOnto | Σ length(path) / \|paths\| (mean owl:Thing-to-leaf path length) |
| RROnto | Σ\|P_C\| / (\|SubClassOf\| + Σ\|P_C\|) — rdf:type, annotation and import triples are not relationships |
| INROnto | \|SubClassOf\| / \|C\| |
| AROnto | datatype-property `rdfs:domain` assertions on named classes / \|C\| |
| CROnto | distinct `rdf:type` assertions to named classes / \|C\| (instances per class) |
| ANOnto | (rdfs:label + rdfs:comment + skos:definition values on classes) / \|C\| |
| PROnto | declared object+datatype properties / (\|SubClassOf\| + that count) |
| TMOnto | \|C_DP\| / \|C\|, C_DP = classes with more than one direct superclass |

Richness metrics are scored with the OQuaRE percentage bands (> 80 % → 5);
per-class averages above 1 fall into the top band. NOCOnto uses the
published band > 12 → 1, (8, 12] → 2, (6, 8] → 3, (3, 6] → 4, ≤ 3 → 5;
CBOOnto and TMOnto use > 8 → 1, (6, 8] → 2, (4, 6] → 3, (2, 4] → 4, ≤ 2 → 5.
Because TMOnto never exceeds 1 it always scores 5; use TMOntoKastor to see
tangling.

| Kastor-adapted variant | Formula | Bands |
|--------|---------|-------|
| NOCOntoKastor | Σ\|Sub_C\| / \|{C : Sub_C ≠ ∅}\| (fan-out of classes that have subclasses) | NOCOnto bands |
| CBOOntoKastor | Σ\|Sup_C ∪ Assoc_C\| / \|C\| (related classes per class) | CBOOnto bands |
| TMOntoKastor | mean number of direct superclasses of classes with more than one direct superclass; 0 without multiple inheritance | 0 → 5, (0, 2] → 4, (2, 4] → 3, (4, 8] → 2, > 8 → 1 |

Published NOCOnto and CBOOnto coincide whenever both only count subclass
edges; NOCOntoKastor measures fan-out and CBOOntoKastor coupling instead.
The reference implementation tecnomod-um/oquare-metrics differs from the
published table in two denominators (NOCOnto divides by the non-leaf
classes, which equals NOCOntoKastor; TMOnto divides by \|C\| − 1); Kastor
follows the published table. Path counts and path
lengths are computed with a memoized, iterative pass, so very deep
(50k-class) or heavily tangled (2^40-path) hierarchies are handled
without recursion or overflow.

**Changed in this release** (previous values are not comparable):
NOCOnto, CBOOnto and TMOnto again use the published OQuaRE definitions and
bands; the Kastor formulas previously reported under those names are now
NOCOntoKastor, CBOOntoKastor and TMOntoKastor (`owl.kastorAdapted` in JSON);
VoID `distinctObjectCount` counts RDF 1.2 triple-term objects. Previously:
DITOnto and LCOMOnto were measured from root classes (depth 0) instead of
owl:Thing, so both are now one higher; NOCOnto and CBOOnto were the same
number (both Σ subclass edges / non-root classes) and now measure fan-out
and coupling respectively; TMOnto scored a diamond like an untangled
hierarchy; SKOS `definitionCoverage` counted `rdfs:comment`; VoID
`distinctObjectCount` merged literals with the same lexical form but a
different datatype or language; `MetricsConfig.useInferredGraph` is
deprecated (it never had an effect). Earlier release:
LCOMOnto was per-leaf max depth / paths; RROnto/INROnto counted every
IRI-to-IRI triple as a relationship; NOC/CBO divided by `|C| − 1`;
RFCOnto omitted superclasses; CROnto was the fraction of classes with
instances; TMOnto was paths per leaf.

### SKOS extensions

conceptCount, prefLabelCoverage, definitionCoverage, orphanConceptCount,
siblingCohortCount, maxSiblingCohortSize. SKOS-specific, not in OQuaRE.

## What it does NOT do

- Compute quality verdicts (single quality scores aggregating multiple
  metrics). This is a measurement library, not an evaluation tool.
- Reason or materialise entailments. Use `:rdf:reasoning` first if you
  want metrics over an inferred graph.
- Validate against SHACL. Use `:tools:onto-quality`.

## Quick start

```kotlin
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.rdf.Rdf

val graph = Rdf.memory().also { /* load Turtle */ }
val report = VocabularyMetrics.compute(graph)
println(report.describeMarkdown())
```

## Output formats

- JSON: convenience for CI dashboards
- Turtle: VoID + kastor-m: vocabulary for triple stores
- Markdown: human-readable reports

## CLI

The `onto-qa metrics` subcommand in `:tools:onto-quality-cli` wraps this module.

## Integration with `:tools:onto-quality`

This library ships **`KastorMetricsProvider`**, which implements the
`MetricsProvider` interface defined in `:tools:onto-quality`. That lets
consumers prioritize SHACL findings by entity importance while keeping
the SHACL module free of a dependency on this metrics artifact.

The metrics APIs remain usable on their own for CI dashboards and other
non-SHACL workflows.

## OQuaRE version

This module pins to Duque-Ramos et al. (2014). Other OQuaRE publications
define some metrics slightly differently; see Reiz & Sandkuhl (2024) for
the harmonisation discussion. We document this in the metric notes.

## References

- Duque-Ramos et al. (2014). PLoS ONE 9(8). https://doi.org/10.1371/journal.pone.0104463
- Reiz & Sandkuhl (2024). Harmonizing the OQuaRE Quality Framework.
- Reference implementation: tecnomod-um/oquare-metrics.
