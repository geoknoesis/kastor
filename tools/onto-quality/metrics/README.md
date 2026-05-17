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
concept.

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
