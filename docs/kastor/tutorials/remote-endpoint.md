## Remote Endpoint

Use a SPARQL server (e.g., Apache Jena Fuseki) to store/query data remotely.

1) Configure endpoints
```kotlin
val api = Rdf.factory {
  providerId = "sparql"
  variantId = "sparql"
  param("baseUrl", "http://localhost:3030")
  param("dataset", "ds")
}
val repo = api.repository
```

2) Query data
```kotlin
val ok = repo.queryAsk("ASK { ?s ?p ?o }")
```

3) Update data (requires update endpoint configured)
```kotlin
repo.update("INSERT DATA { <urn:s> <urn:p> 'o' }")
```




