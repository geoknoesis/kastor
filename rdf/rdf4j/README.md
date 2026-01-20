# RDF4J Dataset Implementation

This module provides a complete implementation of the SPARQL Dataset interface for RDF4J, allowing you to work with both in-memory datasets and repository-backed datasets.

## Overview

A SPARQL Dataset consists of:
- **Default Graph**: An unnamed graph that contains the main data
- **Named Graphs**: Zero or more graphs identified by IRIs that contain additional data

This implementation provides two main classes:
- `Rdf4jDataset`: Repository-backed dataset implementation
- `Rdf4jMemoryDataset`: In-memory dataset implementation

## Features

- ✅ Full SPARQL Dataset interface compliance
- ✅ Repository integration with RDF4J
- ✅ In-memory dataset for lightweight operations
- ✅ Named graph management
- ✅ Graph operations (add, remove, clear)
- ✅ Triple-level operations within graphs
- ✅ Support for all RDF term types (IRIs, literals, blank nodes)
- ✅ **NEW: RDF4J-like API for direct dataset operations**

## Quick Start

### Creating an In-Memory Dataset

```kotlin
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jMemoryDataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.Literal

// Create an in-memory dataset
val dataset = Rdf4jMemoryDataset()

// Add triples to the default graph
val defaultGraph = dataset.defaultGraph()
editDefaultGraph().addTriple(
    RdfTriple(
        Iri("http://example.org/person/1"),
        Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        Iri("http://xmlns.com/foaf/0.1/Person")
    )
)

// Create a named graph
val peopleGraph = Rdf4jMemoryDataset.Rdf4jMemoryGraph()
peopleGraph.addTriple(
    RdfTriple(
        Iri("http://example.org/person/2"),
        Iri("http://xmlns.com/foaf/0.1/name"),
        Literal("Jane Smith")
    )
)

// Add the named graph to the dataset
dataset.addGraph(Iri("http://example.org/graphs/people"), peopleGraph)
```

### Working with Repository Datasets

```kotlin
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository

// Create a memory repository
val repository = Rdf4jRepository.MemoryRepository()

// Add data to default graph
repository.addTriple(
    null, // null means default graph
    RdfTriple(
        Iri("http://example.org/book/1"),
        Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        Iri("http://schema.org/Book")
    )
)

// Add data to a named graph
repository.addTriple(
    Iri("http://example.org/graphs/books"),
    RdfTriple(
        Iri("http://example.org/book/2"),
        Iri("http://schema.org/title"),
        Literal("Sample Book")
    )
)

// Get the dataset from the repository
val dataset = repository.getDataset()

// Access the default graph
val defaultGraph = dataset.defaultGraph()
println("Default graph size: ${defaultGraph.size()}")

// Access named graphs
val booksGraph = dataset.getGraph(Iri("http://example.org/graphs/books"))
println("Books graph size: ${booksGraph?.size()}")

// List all named graph names
val graphNames = dataset.listGraphNames().toList()
println("Named graphs: $graphNames")
```

## API Reference

### RdfDataset Interface

```kotlin
interface RdfDataset {
    // Get the default graph
    fun defaultGraph(): RdfGraph
    
    // Get a named graph by IRI
    fun getGraph(graphName: Iri): RdfGraph?
    
    // List all named graph names
    fun listGraphNames(): Sequence<Iri>
    
    // Check if a named graph exists
    fun containsGraph(graphName: Iri): Boolean
    
    // Get the total number of named graphs
    fun namedGraphCount(): Long
    
    // Optional mutation methods for in-memory datasets
    fun addGraph(graphName: Iri, graph: RdfGraph): Boolean = false
    fun removeGraph(graphName: Iri): Boolean = false
    fun clear(): Boolean = false
}
```

### RdfRepository RDF4J-Like API

The `RdfRepository` interface now provides RDF4J-like methods for direct dataset operations, making it more similar to RDF4J's native `RepositoryConnection` API:

```kotlin
interface RdfRepository {
    // Core dataset operations
    fun getDataset(): RdfDataset
    fun listGraphNames(): Sequence<Iri>
    fun getGraph(graphName: Iri): RdfGraph?
    fun containsGraph(graphName: Iri): Boolean
    fun namedGraphCount(): Long
    
    // RDF4J-like dataset operations for direct named graph management
    fun addNamedGraph(graphName: Iri, triples: Iterable<RdfTriple> = emptyList()): Boolean
    fun removeNamedGraph(graphName: Iri): Boolean
    fun clearNamedGraph(graphName: Iri): Boolean
    fun clearDefaultGraph(): Boolean
    fun clearAllGraphs(): Boolean
    
    // Graph size operations
    fun getGraphSize(graphName: Iri?): Long
    fun getDefaultGraphSize(): Long
    
    // Additional convenience methods
    fun addNamedGraph(graphName: Iri, vararg triples: RdfTriple): Boolean
    fun addNamedGraphFromStatements(graphName: Iri, statements: Iterable<org.eclipse.rdf4j.model.Statement>): Boolean
    fun getNamedGraphStatements(graphName: Iri): Sequence<org.eclipse.rdf4j.model.Statement>
    fun getDefaultGraphStatements(): Sequence<org.eclipse.rdf4j.model.Statement>
    fun hasNamedGraph(graphName: Iri): Boolean
    fun hasDefaultGraph(): Boolean
}
```

#### RDF4J-Like API Examples

```kotlin
// Create a repository
val repository = Rdf4jRepository.MemoryRepository()

// Add named graph with triples
val peopleTriples = listOf(
    RdfTriple(
        Iri("http://example.org/person/1"),
        Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        Iri("http://xmlns.com/foaf/0.1/Person")
    ),
    RdfTriple(
        Iri("http://example.org/person/1"),
        Iri("http://xmlns.com/foaf/0.1/name"),
        Literal("John Doe")
    )
)

val peopleGraphName = Iri("http://example.org/graphs/people")
repository.addNamedGraph(peopleGraphName, peopleTriples)

// Add named graph using vararg method
repository.addNamedGraph(
    Iri("http://example.org/graphs/books"),
    RdfTriple(
        Iri("http://example.org/book/1"),
        Iri("http://schema.org/title"),
        Literal("Sample Book")
    )
)

// Check graph existence and size
println("Has people graph: ${repository.hasNamedGraph(peopleGraphName)}")
println("People graph size: ${repository.getGraphSize(peopleGraphName)}")
println("Default graph size: ${repository.getDefaultGraphSize()}")

// Clear operations
repository.clearNamedGraph(Iri("http://example.org/graphs/books"))
repository.clearDefaultGraph()
repository.clearAllGraphs()

// Get raw RDF4J statements
val statements = repository.getNamedGraphStatements(peopleGraphName).toList()
val defaultStatements = repository.getDefaultGraphStatements().toList()
```

### Rdf4jMemoryDataset

The in-memory dataset provides full mutation capabilities:

```kotlin
class Rdf4jMemoryDataset : RdfDataset {
    // Create a new named graph with triples
    fun addNamedGraph(graphName: Iri, vararg triples: RdfTriple): Boolean
    
    // Create a new named graph with triples from an iterable
    fun addNamedGraph(graphName: Iri, triples: Iterable<RdfTriple>): Boolean
}
```

### Rdf4jMemoryDataset.Rdf4jMemoryGraph

The in-memory graph implementation provides triple-level operations:

```kotlin
class Rdf4jMemoryGraph : RdfGraph {
    // Add a single triple
    fun addTriple(triple: RdfTriple): Boolean
    
    // Remove a triple
    fun removeTriple(triple: RdfTriple): Boolean
    
    // Clear all triples
    fun clear(): Boolean
    
    // Add multiple triples
    fun addTriples(vararg triples: RdfTriple)
    fun addTriples(triples: Iterable<RdfTriple>)
}
```

## SPARQL Dataset Operations

The dataset implementation supports all standard SPARQL dataset operations:

### FROM Clause (Default Graph)
```sparql
SELECT ?s ?p ?o
FROM <http://example.org/graphs/data>
WHERE { ?s ?p ?o }
```

### FROM NAMED Clause (Named Graphs)
```sparql
SELECT ?s ?p ?o
FROM NAMED <http://example.org/graphs/people>
FROM NAMED <http://example.org/graphs/books>
WHERE { ?s ?p ?o }
```

### GRAPH Clause (Accessing Named Graphs)
```sparql
SELECT ?name
WHERE {
  GRAPH <http://example.org/graphs/people> {
    ?person <http://xmlns.com/foaf/0.1/name> ?name
  }
}
```

## Examples

See `Rdf4jDatasetExample.kt` for comprehensive examples demonstrating:

1. **In-Memory Dataset Operations**: Creating, populating, and managing in-memory datasets
2. **Repository Integration**: Working with repository-backed datasets
3. **SPARQL Dataset Operations**: Managing named graphs and default graphs
4. **Data Management**: Adding, removing, and querying graphs and triples

## Testing

Run the test suite to verify functionality:

```bash
./gradlew :rdf:rdf4j:test
```

The tests cover:
- Dataset creation and management
- Graph operations (add, remove, clear)
- Triple operations within graphs
- Repository integration
- Edge cases and error conditions

## Performance Considerations

- **In-Memory Datasets**: Fast for small to medium datasets, but consume memory
- **Repository Datasets**: Better for large datasets, persistent storage, and concurrent access
- **Graph Operations**: Use `containsGraph()` before `getGraph()` to avoid unnecessary graph creation
- **Batch Operations**: Use `addTriples()` for adding multiple triples at once

## Integration with Core RDF Module

This implementation integrates seamlessly with the core RDF module:

```kotlin
import com.geoknoesis.kastor.rdf.Rdf

// Create a dataset using the factory DSL
val dataset = Rdf.dataset {
    providerId = "rdf4j"
    variantId = "memory"
}

// Create a live graph from a dataset
val liveGraph = Rdf.liveGraph("http://example.org/graphs/data") {
    providerId = "rdf4j"
    variantId = "memory"
}
```

## Contributing

When contributing to this module:

1. Ensure all tests pass
2. Add tests for new functionality
3. Follow the existing code style
4. Update documentation for new features
5. Consider performance implications for large datasets

## License

This module is part of the Kastor RDF framework and follows the same licensing terms.
