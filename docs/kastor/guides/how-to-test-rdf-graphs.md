# How to test RDF graphs (golden Turtle, isomorphism, CLI)

{% include version-banner.md %}

## What you'll learn

- Compare an in-memory graph to **expected Turtle** without fighting blank node labels
- Read **assertion failures** that include sorted N-Triples snippets and an optional strict triple diff
- Use the **`kastor-rdf` CLI** to validate files, re-emit Turtle, or diff two graphs from the shell

## Prerequisites

- **`com.geoknoesis.kastor:rdf-testkit`** on the **test** classpath (it brings Jena transitively for isomorphism and diagnostics)
- A concrete RDF provider for `Rdf.parse` / `Rdf.memory()` in tests (for example **`rdf-jena`**), same as for other Kastor tests

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    testImplementation("com.geoknoesis.kastor:rdf-testkit:0.2.0")
    testImplementation("com.geoknoesis.kastor:rdf-jena:0.2.0")
}
```

When you use the [Kastor BOM](../getting-started/installation.md), align versions via the BOM instead of repeating `0.2.0`.

## Golden Turtle assertions (blank-node tolerant)

`assertGraphIsomorphicTurtle` parses the expected Turtle, then checks **RDF isomorphism** against your actual graph (blank nodes may use different internal ids).

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.testing.assertGraphIsomorphicTurtle
import com.geoknoesis.kastor.rdf.testing.assertDefaultGraphIsomorphicTurtle
import org.junit.jupiter.api.Test

class MyDslTest {

    @Test
    fun `catalog DSL matches fixture`() {
        val repo = Rdf.memory()
        repo.add {
            // … your DSL …
        }

        val expected = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            <http://example.org/ds> a dcat:Dataset ;
                dcat:title "Example" .
        """.trimIndent()

        assertDefaultGraphIsomorphicTurtle(expected, repo)
    }
}
```

Use **`assertGraphIsomorphic`** when both sides are already **`RdfGraph`** instances (for example two `Rdf.graph { }` builders).

## Strict triple diff (debugging)

Isomorphism ignores blank node identity. If you need to see **exact** `RdfTriple` differences (same blank node ids on both sides), use **`strictGraphDiff`** from the same package. Failed **`assertGraphIsomorphic`** messages also include a capped strict diff when the underlying triple sets are not literally equal.

## Sorted N-Triples snapshots

For custom assertions or logging, **`RdfGraphSnapshots.sortedNtriplesString(graph)`** returns a stable, line-sorted N-Triples view (labels for blank nodes follow the serializer, so prefer isomorphism for equality).

## `kastor-rdf` CLI

The **`:rdf:cli`** module ships a small entry point for local checks and CI scripts.

Run via Gradle (works on any OS):

```bash
./gradlew :rdf:cli:run --args="help"
./gradlew :rdf:cli:run --args="parse path/to/file.ttl"
./gradlew :rdf:cli:run --args="to-turtle path/to/file.nt NT"
./gradlew :rdf:cli:run --args="diff expected.ttl actual.ttl"
```

Commands:

| Command | Purpose |
|--------|---------|
| **`parse`** | Parse a file, print triple count (non-zero exit on parse errors) |
| **`to-turtle`** | Parse with inferred or explicit format, print **Turtle** to stdout |
| **`diff`** | **Isomorphism** check between two files; exit code **2** if they differ structurally |

Format is inferred from the file extension when omitted (`.ttl` → Turtle, `.nt` → N-Triples, `.jsonld` → JSON-LD, and so on). A third argument overrides the format for **`diff`** (both files use the same override).

> The CLI does not execute arbitrary Kotlin DSL scripts; build graphs in tests or apps, then **`to-turtle`** or **`diff`** serialized files.

## Related

- [How to parse RDF](how-to-parse-rdf.md)
- [How to serialize RDF](how-to-serialize-rdf.md)
- [Compact DSL guide](../api/compact-dsl-guide.md)
