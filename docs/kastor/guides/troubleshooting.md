# Troubleshooting

{% include version-banner.md %}

> **Documentation mode: Reference** — symptom → likely cause. **Deeper fixes:** [Error handling](error-handling.md), [Debug mode](debug-mode.md), [Android & KMP](android-kmp.md).

## Problem

- Resolve common runtime or integration failures quickly.

## Symptoms

### "Unsupported RDF format"

- Pass a **`RdfFormat`** enum value or a parser-known format string (**`TURTLE`**, **`JSONLD`**, …). See [How to Parse RDF](how-to-parse-rdf.md).

### "No provider found" / repository creation fails

- Add the provider module (**`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**) to the classpath for that source set.
- On Android / KMP, register **`RdfProvider` instances explicitly ([Android & KMP](android-kmp.md)).
- List ids with **`RdfProviderRegistry.discoverProviders()`** or **`supports("jena")`** checks.

### Remote SPARQL errors

- Confirm **`location`** is reachable, supports **`POST`** with **`application/sparql-query`** / **`application/sparql-update`** on that URL, and credentials/proxies match your environment ([Remote SPARQL endpoint](../tutorials/remote-endpoint.md)).

### Empty `SELECT` results

- Data may live in a **named graph** while the query targets the **default graph**—add **`GRAPH`** or load into **`editDefaultGraph`** ([How to Use Datasets](how-to-use-datasets.md)).
- Compare IRIs literally (trailing slashes, encoding).

### Writes appear missing on HTTP repositories

- Verify **`UPDATE`** succeeded (HTTP status); some servers require separate query/update URLs—Kastor’s SPARQL client uses one **`location`** for both.

### Unexpected RDF-star / reifier behavior after upgrade

- See [Migrating to RDF 1.2](migrating-to-rdf-1.2.md) for subject-position triple terms and snapshot updates.

## Related

- [FAQ](faq.md)
- [Performance](performance.md)
- [Conformance](../concepts/rdf-1.2-conformance.md)
