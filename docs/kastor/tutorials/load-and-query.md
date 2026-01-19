## Load and Query

1) Start with an in-memory repository
```kotlin
val api = Rdf.factory {
  providerId = "jena"
  variantId = "memory"
}
val repo = api.repository
```

2) Load Turtle content
```kotlin
val turtle = """
@prefix ex: <urn:ex:> .
ex:s ex:p "o" .
""".trimIndent()
repo.beginTransaction()
repo.readGraph(null, turtle.byteInputStream(), "TURTLE")
repo.commit(); repo.end()
```

3) Run a SELECT query
```kotlin
val rows = repo.querySelect("SELECT ?s WHERE { ?s <urn:ex:p> ?o }")
```




