## Remote SPARQL endpoint

{% include version-banner.md %}

> **Documentation mode: Tutorial** — connect Kastor to a running SPARQL HTTP service. Terms (**endpoint**, **dataset**) → [**Glossary**](../concepts/glossary.md). Task-only wiring → [How to Use Datasets](../guides/how-to-use-datasets.md).

### Goal

Configure the **`rdf-sparql`** provider, run one **`ASK`** query, and optionally issue a **`SPARQL UPDATE`** against the same service URL.

### Prerequisites

- JDK **17**+, Gradle + Kotlin (see [Installation](../getting-started/installation.md))
- Dependencies at **`0.2.0`**: `rdf-core` plus **`rdf-sparql`** (declared explicitly or via the [Kastor BOM](../getting-started/installation.md)). The SPARQL provider is registered via Java **`ServiceLoader`** from the `rdf-sparql` artifact.
- A reachable SPARQL **1.1** or **1.2** HTTP endpoint (for example [Apache Jena Fuseki](https://jena.apache.org/documentation/fuseki2/)) that accepts:
  - **`POST`** with `Content-Type: application/sparql-query` for queries, and
  - **`POST`** with `Content-Type: application/sparql-update` for updates  
  on the **same URL** you configure as `location` (some servers split `/query` and `/update`; this provider uses one URL for both—use a gateway or a server layout that matches).

### What you'll build

A small Kotlin snippet that opens a remote repository and probes it with `ASK`, then optionally inserts one triple with `UPDATE`.

### Step 1: Point Kastor at the endpoint

The builder threads `location` through to the provider as the SPARQL service URL (same URL for **`application/sparql-query`** and **`application/sparql-update`** posts):

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.UpdateQuery

val repo = Rdf.repository {
    providerId = "sparql"
    variantId = "sparql"
    location = "http://localhost:3030/ds/sparql"
}

repo.use {
    val before = it.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))
    println("ASK before insert: $before")

    it.update(UpdateQuery("INSERT DATA { <urn:ex:s> <urn:ex:p> \"o\" . }"))

    val after = it.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))
    println("ASK after insert: $after")
}
```

Replace `location` with the URL your server documents for SPARQL HTTP POST.

### Step 2: Lifecycle

Prefer **`use { }`** so **`Closeable`** repositories shut down cleanly. For long-lived beans (for example Spring), hold one **`RdfRepository`** instance and **`close()`** on application stop.

## Validation

- **`ASK before insert`** prints **`false`** on an empty dataset (no **`?s ?p ?o`** yet).
- **`ASK after insert`** prints **`true`** once the **`INSERT DATA`** triple is visible on the default graph your endpoint exposes.

## Troubleshooting

- **`IllegalArgumentException: SPARQL endpoint URL required`:** Set `location = "..."` on the repository builder (the SPARQL provider reads the `"location"` option).
- **Updates fail while queries work:** The server may require a different path for updates than for queries. This provider sends both to **`location`**; align your reverse proxy or Fuseki layout, or open an enhancement request if you need split URLs.
- **Authentication / TLS:** Configure certificates and HTTP credentials outside Kastor (JVM trust stores, corporate proxies) as for any JVM HTTP client.

## Related

- [How to Use Datasets](../guides/how-to-use-datasets.md)
- [How to work with named graphs](../guides/how-to-named-graphs.md)
- [SPARQL fundamentals](../concepts/sparql-fundamentals.md)
