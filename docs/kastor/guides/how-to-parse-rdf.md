# How to Parse RDF into a Graph

{% include version-banner.md %}

> **Documentation mode: How-to guide** — task-focused steps. **Explanation:** [RDF Fundamentals](../concepts/rdf-fundamentals.md). **Reference:** parsing overloads and formats → [API](../api/api-reference.md), `RdfFormat`.

## Problem

Load RDF from **strings**, **files**, or **URLs** into a Kastor **graph** (and optionally merge into a **repository** for querying).

## Prerequisites

- A JVM dependency that includes **`rdf-core`** and at least one **provider** that supports the format (e.g. `rdf-jena` / `rdf-rdf4j`). Providers are usually discovered automatically on JVM.

## Steps

### Step 1: Parse RDF from a string

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat

val turtleData = """
    @prefix foaf: <http://xmlns.com/foaf/0.1/> .
    <http://example.org/alice> foaf:name "Alice Johnson" ;
                                foaf:age 30 .
"""

val graph = Rdf.parse(turtleData, format = RdfFormat.TURTLE)
println("Parsed triples: ${graph.getTriples().size}")
```

### Step 2: Parse RDF from a file

```kotlin
val graph = Rdf.parseFromFile("data.ttl", format = RdfFormat.TURTLE)

val graph2 = Rdf.parseFromFile("data.jsonld", format = RdfFormat.JSON_LD)
```

### Step 3: Parse RDF from a URL

```kotlin
val remoteGraph = Rdf.parseFromUrl(
    "https://example.org/data.ttl",
    format = RdfFormat.TURTLE
)

val remoteGraph2 = Rdf.parseFromUrl(
    "https://example.org/data.jsonld",
    format = RdfFormat.JSON_LD
)
```

If you want to avoid blocking the current thread, use the async variant:

```kotlin
val future = Rdf.parseFromUrlAsync(
    "https://example.org/data.ttl",
    format = RdfFormat.TURTLE
)

val remoteGraphAsync = future.get() // or attach callbacks
```

URL loading is restricted by `UrlLoadOptions`. By default only `http` and `https` URLs are accepted, and response bodies larger than 64 MiB fail with `RdfInputTooLargeException`. Pass options to change this:

```kotlin
val local = Rdf.parseFromUrl(
    "file:///data/big.ttl",
    format = "TURTLE",
    options = UrlLoadOptions(allowedSchemes = setOf("file"), maxBytes = 512L * 1024 * 1024)
)
```

HTTP redirects are followed (up to 10 hops, never from `https` down to `http`), including redirects to another host. When the URL comes from untrusted input and your process can reach hosts the caller must not (loopback, cloud metadata, other internal addresses), pass an address policy. It is asked about the URL the load starts with and about every redirect target:

```kotlin
val graph = Rdf.parseFromUrl(
    userSuppliedUrl,
    format = "TURTLE",
    // or your own: UrlAddressPolicy { url -> url.host in allowedHosts }
    options = UrlLoadOptions(addressPolicy = UrlAddressPolicy.PUBLIC_ADDRESSES)
)
```

`UrlAddressPolicy.PUBLIC_ADDRESSES` allows a URL only if its host is, or resolves only to, public unicast addresses. It refuses loopback, link-local, private, carrier-grade NAT, unique-local, documentation, reserved, wildcard and multicast ranges, also when an IPv4 address is embedded in an IPv6 one (IPv4-mapped, NAT64, 6to4). A refused URL fails with `RdfAddressRefusedException`. It narrows what a URL can reach; it is not a guarantee: host names are looked up when the policy is asked and again by the connection, so it does not stop DNS rebinding, and behind an HTTP proxy the local lookup says nothing about where the proxy connects. Use network-level controls where that matters. `UrlLoadOptions.redirectPolicy` remains for rules about redirects only (for example `UrlRedirectPolicy.SAME_HOST`).

### Step 4: Add parsed graph to a repository

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.FOAF

val repo = Rdf.memory()
repo.addTriples(graph.getTriples())

// Now you can query the repository
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?age WHERE {
        ?person ${FOAF.name} ?name .
        ?person ${FOAF.age} ?age .
    }
"""))

results.forEach { binding ->
    println("${binding.getString("name")} is ${binding.getInt("age")} years old")
}
```

## Validation

You should see non-empty triple counts after parse; the Step 4 snippet should print bindings similar to:

```
Parsed triples: 2
Alice Johnson is 30 years old
```

## Troubleshooting

- **`RdfFormatException`** — no provider registered for that format; add a backend module or register a provider explicitly ([Android/KMP](../guides/android-kmp.md) often requires explicit registration).
- **Empty graph** — wrong **syntax** vs declared `RdfFormat`, or empty input.
- **URL timeouts** — remote fetch uses timeout protection (~30s); check network or mirror the file locally.

## Supported formats (quick lookup)

The following formats are supported:

- **Turtle**: `RdfFormat.TURTLE`
- **JSON-LD**: `RdfFormat.JSON_LD`
- **RDF/XML**: `RdfFormat.RDF_XML`
- **N-Triples**: `RdfFormat.N_TRIPLES`

## Related tasks

- [Serialize RDF](how-to-serialize-rdf.md)
- [Use datasets / named graphs](how-to-use-datasets.md)
- [Test RDF graphs](how-to-test-rdf-graphs.md)