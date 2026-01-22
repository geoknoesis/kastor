## Repository

### Transactions
- `transaction { ... }`
- `readTransaction { ... }`

### Queries
- `select(SparqlSelect): SparqlQueryResult`
- `ask(SparqlAsk): Boolean`
- `construct(SparqlConstruct): Sequence<RdfTriple>`
- `describe(SparqlDescribe): Sequence<RdfTriple>`

### Updates
- `update(UpdateQuery)`

### Graph operations
- `defaultGraph: RdfGraph`
- `getGraph(name: Iri): RdfGraph`
- `listGraphs(): List<Iri>`
- `createGraph(name: Iri): RdfGraph`
- `removeGraph(name: Iri): Boolean`
- `editDefaultGraph(): GraphEditor`
- `editGraph(name: Iri): GraphEditor`

### Behavior by provider
- Jena: transactions are supported; performance varies by backend.
- RDF4J: transactions supported; suitable for production workloads.
- SPARQL: remote endpoints may ignore transactions; use `update` and `select`/`ask`.




