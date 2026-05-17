# How to test RDF graphs (golden Turtle, isomorphism, CLI)

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** RDF identity vs isomorphism (especially **blank nodes**) → [RDF Fundamentals](../concepts/rdf-fundamentals.md). **Reference:** `rdf-testkit` helpers in source.

## Problem

- Assert that a built graph **matches expected Turtle** without brittle blank-node ids.
- Debug mismatches with **strict triple diffs** when needed.
- Run quick **CLI** checks (`parse`, `to-turtle`, `diff`) from Gradle or scripts.

## Prerequisites

- **`com.geoknoesis.kastor:rdf-testkit`** on the **test** classpath (brings Jena transitively for isomorphism and diagnostics).
- A concrete RDF provider for `Rdf.parse` / `Rdf.memory()` (for example **`rdf-jena`**).

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    testImplementation("com.geoknoesis.kastor:rdf-testkit:0.2.0")
    testImplementation("com.geoknoesis.kastor:rdf-jena:0.2.0")
}
```

When you use the [Kastor BOM](../getting-started/installation.md), align versions via the BOM instead of repeating `0.2.0`.

## Steps

### Golden Turtle assertions (blank-node tolerant)

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

### Strict triple diff (debugging)

Isomorphism ignores blank node identity. If you need **exact** `RdfTriple` differences (same blank node ids on both sides), use **`strictGraphDiff`** from the same package. Failed **`assertGraphIsomorphic`** messages also include a capped strict diff when the underlying triple sets are not literally equal.

### Sorted N-Triples snapshots

For custom assertions or logging, **`RdfGraphSnapshots.sortedNtriplesString(graph)`** returns a stable, line-sorted N-Triples view (blank node labels follow the serializer — prefer isomorphism for equality).

### `kastor-rdf` CLI

The **`:rdf:cli`** module ships a small entry point for local checks and CI scripts.

Run via Gradle:

```bash
./gradlew :rdf:cli:run --args="help"
./gradlew :rdf:cli:run --args="parse path/to/file.ttl"
./gradlew :rdf:cli:run --args="to-turtle path/to/file.nt NT"
./gradlew :rdf:cli:run --args="diff expected.ttl actual.ttl"
```

| Command | Purpose |
|--------|---------|
| **`parse`** | Parse a file, print triple count (non-zero exit on parse errors) |
| **`to-turtle`** | Parse with inferred or explicit format, print **Turtle** to stdout |
| **`diff`** | **Isomorphism** check between two files; exit code **2** if they differ structurally |

Format is inferred from the file extension when omitted (`.ttl` → Turtle, `.nt` → N-Triples, `.jsonld` → JSON-LD, and so on). A third argument overrides the format for **`diff`** (both files use the same override).

> The CLI does not execute arbitrary Kotlin DSL scripts; build graphs in tests or apps, then **`to-turtle`** or **`diff`** serialized files.

## Validation

- JUnit: assertion passes and prints nothing beyond your test runner output.
- CLI: `parse` exits **0** with a positive triple count for valid input; `diff` exits **0** when graphs are isomorphic.

## Troubleshooting

- **Classpath / `NoClassDefFoundError` in tests** — ensure **`rdf-testkit`** and a provider are both on **test** classpath.
- **Isomorphism passes but wrong data** — isomorphism checks structure, not human-readable Turtle layout; compare semantics or use stricter checks for literals/datatypes.
- **CLI `diff` exit 2** — graphs differ; re-run with `to-turtle` on both sides and compare, or use sorted N-Triples snapshots in Kotlin tests.

## Related tasks

- [How to parse RDF](how-to-parse-rdf.md)
- [How to serialize RDF](how-to-serialize-rdf.md)
- [Compact DSL guide](../api/compact-dsl-guide.md)
