## Cookbook

### Discover Configuration Parameters
```kotlin
// Get all available repository types with parameter details
val variants = RdfApiRegistry.getAllConfigVariants()
variants.forEach { variant ->
    println("${variant.type}: ${variant.description}")
    variant.parameters.forEach { param ->
        println("  ${param.name} (${param.type}): ${param.description}")
        if (param.examples.isNotEmpty()) {
            println("    Examples: ${param.examples.joinToString(", ")}")
        }
    }
}

// Get parameter information for specific variant
val jenaTdb2 = RdfApiRegistry.getConfigVariant("jena:tdb2")
val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
println("Location parameter: ${locationParam?.description}")
println("Examples: ${locationParam?.examples?.joinToString(", ")}")

// Validate configuration before creating repository
val requiredParams = RdfApiRegistry.getRequiredParameters("sparql")
val config = RdfConfig(
    providerId = "sparql",
    variantId = "sparql",
    options = mapOf("location" to "http://dbpedia.org/sparql")
)
val missingParams = requiredParams.filter { param -> 
    !config.options.containsKey(param.name) 
}
if (missingParams.isEmpty()) {
    println("Configuration is valid")
} else {
    println("Missing required parameters: ${missingParams.map { it.name }}")
}
```

### Create Repository with Parameter Validation
```kotlin
// Create repository with validated parameters
val variant = RdfApiRegistry.getConfigVariant("jena:tdb2")
if (variant != null) {
    val requiredParams = variant.parameters.filter { !it.optional }
    val params = mapOf("location" to "/data/tdb2")
    
    val missingParams = requiredParams.filter { param -> 
        !params.containsKey(param.name) 
    }
    
    if (missingParams.isEmpty()) {
        val repo = RdfApiRegistry.create(
            RdfConfig(providerId = "jena", variantId = "tdb2", options = params)
        )
        println("Repository created successfully")
    } else {
        println("Missing required parameters: ${missingParams.map { it.name }}")
    }
}
```

### Insert a triple
```kotlin
repo.update(UpdateQuery("INSERT DATA { <urn:s> <urn:p> 'o' }")))
```

### Create triples using the DSL
```kotlin
val alice = iri("ex:alice")
val name = iri("foaf:name")
val age = iri("foaf:age")

// Natural language syntax
val triple1 = alice has name with "Alice"
val triple2 = alice has age with 30

// Add to repository
repo.addTriple(null, triple1)
repo.addTriple(null, triple2)
```

### Load Turtle from file
```kotlin
java.nio.file.Files.newInputStream(java.nio.file.Paths.get("./data.ttl")).use { ins ->
  repo.beginTransaction();
  repo.readGraph(null, ins, "TURTLE");
  repo.commit(); repo.end();
}
```

### Export JSON-LD
```kotlin
val out = java.io.ByteArrayOutputStream()
repo.writeGraph(null, out, "JSONLD")
```

### Labels in English
```sparql
SELECT ?s ?label WHERE {
  ?s rdfs:label ?label
  FILTER (langMatches(lang(?label), 'EN'))
}
```

### Named graph only
```sparql
CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:g> { ?s ?p ?o } }
```




