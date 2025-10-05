## Hello, RDF

This tutorial assumes no prior RDF knowledge.

1) Create an in-memory repository
```kotlin
val api = Rdf.factory { type("jena:memory") }
val repo = api.repository
```

2) Insert one fact: subject–predicate–object
```kotlin
repo.update("INSERT DATA { <urn:alice> <urn:knows> <urn:bob> }")
```

3) Ask if the fact exists
```kotlin
val exists = repo.queryAsk("ASK { <urn:alice> <urn:knows> <urn:bob> }")
```

You’ve created your first RDF data and queried it.

