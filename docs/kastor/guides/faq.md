# FAQ

{% include version-banner.md %}

> **Documentation mode: Reference** — quick answers. **Terms:** [**Glossary**](../concepts/glossary.md). **Orientation:** [How documentation fits together](../getting-started/documentation-guide.md).

## Problem

- Answer recurring **“how do I …?”** questions about providers, persistence, transactions, and loading RDF.

## Providers

**Which providers exist?**  
Common **`providerId`** values include **`jena`**, **`rdf4j`**, **`sparql`**, and the always-available **`memory`** implementation bundled via **`DefaultProviderRegistry`**. Inspect **`RdfProviderRegistry.discoverProviders()`** at runtime ([Cookbook](cookbook.md)).

**How do I choose one?**

- **`jena`** + **`memory`** variant: tests and small in-memory workloads.
- **`jena`** + **`tdb2`** or **`rdf4j`** + **`native`**: durable local stores ([Configuration variants](../getting-started/configuration-variants.md)).
- **`sparql`**: HTTP endpoint; set **`location`** to the service URL ([Remote SPARQL endpoint](../tutorials/remote-endpoint.md)).

**Android / KMP:** Prefer explicit **`RdfProviderRegistry.register(...)`** ([Android & KMP](android-kmp.md)).

## Transactions

**Are transactions required?**  
Use **`repo.transaction { … }`** to batch logical writes on backends that support richer semantics; many implementations execute the block synchronously. Remote SPARQL repositories typically **do not** offer ACID transactions—treat the block as sequential operations ([How to Use Transactions](how-to-transactions.md)).

## Loading and graphs

**How do I load files?**  
Use **`Rdf.parseFromFile`**, **`Rdf.parse`**, or **`Rdf.parseStreaming`** ([How to Parse RDF](how-to-parse-rdf.md)).

**How do I target a named graph?**  
Use **`repo.addToGraph(graphIri) { … }`**, **`repo.createGraph`**, or SPARQL **`GRAPH`** patterns ([How to Work with Named Graphs](how-to-named-graphs.md)).

## RDF-star and RDF 1.2

**Does quoted-triple / RDF-star style data work?**  
Kastor **0.2.0** targets **RDF 1.2**; **`TripleTerm`** and reifiers replace illegal triple-term subjects. Capability flags include **`supportsTripleTerms`** ([Migrating to RDF 1.2](migrating-to-rdf-1.2.md)).

## Related

- [Troubleshooting](troubleshooting.md)
- [Getting Started](../getting-started/getting-started.md)
