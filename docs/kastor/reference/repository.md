## Repository

### Transactions
- `beginTransaction(write: Boolean = true)`
- `commit()`
- `rollback()`
- `end()`

### Queries
- `querySelect(query: String, bindings: Map<String, RdfTerm> = emptyMap()): ResultSet`
- `queryConstruct(query: String, bindings: Map<String, RdfTerm> = emptyMap()): RdfGraph`
- `queryAsk(query: String, bindings: Map<String, RdfTerm> = emptyMap()): Boolean`

### Updates
- `update(update: String, bindings: Map<String, RdfTerm> = emptyMap())`

### Data operations
- `addTriple(graph: Iri?, triple: RdfTriple)`
- `readGraph(graph: Iri?, input: InputStream, format: String)`
- `writeGraph(graph: Iri?, output: OutputStream, format: String)`

### Behavior by provider
- Jena: auto-read/write transactions if none active; bindings not applied automatically.
- RDF4J: auto-commit if no transaction; bindings applied.
- SPARQL: remote HTTP; transactions no-ops; `addTriple` unsupported; inline values.




