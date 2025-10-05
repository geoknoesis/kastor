## FAQ

- **What providers are available?** `jena`, `rdf4j`, and `sparql`.
- **How do I pick a provider?**
  - Use `jena:memory` for tests and small in-memory tasks.
  - Use `jena:tdb2` or `rdf4j:native` for on-disk persistence.
  - Use `sparql`/`sparql:fuseki` to talk to remote endpoints.
- **Are transactions required?** For Jena and RDF4J, use transactions to batch writes. Remote SPARQL ignores transactions.
- **How do I load files?** Open an `InputStream` and pass a format string like `"TURTLE"` to `readGraph`.
- **How do I add named graph data?** Pass an `Iri` as the first `graph` argument to `addTriple`/`readGraph`/`writeGraph`.
- **Does RDF-star work?** Jena mappings for `TripleTerm` exist; RDF4J-star mapping is not implemented in this scaffold.

