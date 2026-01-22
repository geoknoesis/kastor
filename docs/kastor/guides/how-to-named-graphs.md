# How to Work with Named Graphs

{% include version-banner.md %}

## What you'll learn
- Create and write to named graphs
- Query named graphs using SPARQL

## Step 1: Create a named graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri

val repo = Rdf.memory()
val graphName = iri("http://example.org/graphs/metadata")

repo.createGraph(graphName)
```

## Step 2: Add data to the named graph

```kotlin
repo.addToGraph(graphName) {
    val dataset = iri("http://example.org/dataset/1")
    dataset has "http://purl.org/dc/terms/title" with "Sample Dataset"
}
```

## Step 3: Query the named graph

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

val results = repo.select(SparqlSelectQuery("""
    SELECT ?title WHERE {
        GRAPH <http://example.org/graphs/metadata> {
            <http://example.org/dataset/1> <http://purl.org/dc/terms/title> ?title .
        }
    }
"""))

results.forEach { row ->
    println(row.getString("title"))
}
```

## Expected output

```
Sample Dataset
```

## Notes
- Use `addToGraph` to target a specific named graph.
- Use `GRAPH <iri>` in SPARQL to query named graphs.

