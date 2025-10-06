## Provider Comparison

| Capability | Jena | RDF4J | SPARQL (remote) |
|---|---|---|---|
| In-memory store | Yes (`jena:memory`) | Yes (`rdf4j:memory`) | N/A |
| Persistent store | Yes (TDB2) | Yes (NativeStore) | Server-dependent |
| Transactions | Yes | Yes | No-ops |
| SPARQL SELECT/CONSTRUCT/ASK | Yes | Yes | Yes |
| SPARQL UPDATE | Yes | Yes | Yes (if `updateEndpoint`) |
| Named graphs | Yes | Yes | Yes |
| Variable bindings map | Not applied | Applied | Not applied in queries |

Choose based on deployment: embedded (Jena/RDF4J) vs remote (SPARQL), and storage needs (memory vs disk).

