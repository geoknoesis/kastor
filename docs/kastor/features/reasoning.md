# Kastor Reasoning Framework

Design note (architecture, registry, onto-quality v0.4 integration): **[Reasoning in Kastor](../design/reasoning-in-kastor.md)**.

The Kastor RDF framework now includes a comprehensive reasoning system that provides pluggable reasoning capabilities through a provider mechanism, similar to the existing RDF provider architecture.

## 🏗️ **Architecture Overview**

### **Core Components**

1. **`RdfReasonerProvider`** - Interface for reasoner providers
2. **`RdfReasoner`** - Core reasoner interface
3. **`ReasonerConfig`** - Configuration for reasoning operations
4. **`ReasonerRegistry`** - Central registry for discovering reasoners
5. **`ReasoningResult`** - Comprehensive reasoning results

### **Supported Reasoner Types**

- **RDFS** - RDF Schema reasoning
- **OWL-EL** - OWL 2 EL profile reasoning
- **OWL-RL** - OWL 2 RL profile reasoning
- **OWL-DL** - Full OWL 2 DL reasoning
- **Custom** - Custom rule-based reasoning

## 📦 **Module Structure**

```
rdf/
├── core/                    # Core RDF interfaces (no reasoning)
├── reasoning/               # Dedicated reasoning module
│   ├── RdfReasonerProvider.kt
│   ├── RdfReasoner.kt
│   ├── ReasonerConfig.kt
│   ├── ReasoningResults.kt
│   ├── ReasonerRegistry.kt
│   ├── RdfReasoning.kt      # Factory object
│   └── providers/
│       └── MemoryReasonerProvider.kt
├── jena-reasoning/          # Jena RdfReasonerProvider (+ SPI)
│   └── reasoning/
│       └── JenaReasonerProvider.kt
├── rdf4j-reasoning/         # RDF4J RdfReasonerProvider (+ SPI)
│   └── reasoning/
│       └── Rdf4jReasonerProvider.kt
└── examples/                # Reasoning examples
    ├── BasicReasoningExample.kt
    └── ReasonerProviderExample.kt
```

## 🚀 **Usage Examples**

### **Basic Reasoning**

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS

// Create a graph with RDFS schema
val graph = Rdf.graph {
    prefixes {
        put("ex", "http://example.org/")
    }
    
    // Define class hierarchy
    val person = iri("ex:Person")
    val student = iri("ex:Student")
    val teacher = iri("ex:Teacher")
    
    person - RDFS.subClassOf - RDFS.Resource
    student - RDFS.subClassOf - person
    teacher - RDFS.subClassOf - person
    
    // Define property hierarchy
    val knows = iri("ex:knows")
    val teaches = iri("ex:teaches")
    
    knows - RDFS.subPropertyOf - RDFS.seeAlso
    teaches - RDFS.subPropertyOf - knows
    
    // Domain and range
    knows - RDFS.domain - person
    knows - RDFS.range - person
    
    // Instances
    val alice = iri("ex:alice")
    val bob = iri("ex:bob")
    
    alice - RDF.type - student
    bob - RDF.type - teacher
    alice - teaches - bob
}

// Create a reasoner
val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)

// Perform reasoning
val result = reasoner.reason(graph)

println("Found ${result.inferredTriples.size} inferred triples")
println("Graph is consistent: ${result.consistencyCheck.isConsistent}")

// Show inferred triples
result.inferredTriples.forEach { triple ->
    println("  $triple")
}
```

### **Different Reasoner Providers**

```kotlin
// Get available providers
val providers = RdfReasoning.reasonerProviders()
providers.forEach { provider ->
    println("${provider.name} (${provider.id}) - v${provider.version}")
    println("  Supported types: ${provider.getSupportedTypes().joinToString(", ")}")
}

// Use specific provider
val jenaReasoner = providers.find { it.id == "jena" }?.createReasoner(ReasonerConfig.rdfs())
val rdf4jReasoner = providers.find { it.id == "rdf4j" }?.createReasoner(ReasonerConfig.rdfs())
```

### **Configuration Options**

```kotlin
// Default configuration
val defaultConfig = ReasonerConfig.default()

// RDFS-specific configuration
val rdfsConfig = ReasonerConfig.rdfs()

// OWL-EL configuration
val owlElConfig = ReasonerConfig.owlEl()

// Large graph configuration
val largeGraphConfig = ReasonerConfig.forLargeGraphs()

// Memory-constrained configuration
val memoryConfig = ReasonerConfig.forMemoryConstrained()

// Custom configuration
val customConfig = ReasonerConfig(
    reasonerType = ReasonerType.RDFS,
    enabledRules = setOf(
        ReasoningRule.RDFS_SUBCLASS,
        ReasoningRule.RDFS_SUBPROPERTY
    ),
    timeout = Duration.ofMinutes(10),
    includeAxioms = true
)
```

## 🔧 **Reasoning Results**

The `ReasoningResult` provides comprehensive information about the reasoning process:

```kotlin
data class ReasoningResult(
    val originalGraph: RdfGraph,
    val inferredTriples: List<RdfTriple>,
    val classification: ClassificationResult?,
    val consistencyCheck: ConsistencyResult,
    val reasoningTime: Duration,
    val statistics: ReasoningStatistics
)
```

### **Classification Results**

```kotlin
data class ClassificationResult(
    val classHierarchy: Map<Iri, List<Iri>>,      // class -> superclasses
    val instanceClassifications: Map<Iri, List<Iri>>, // instance -> types
    val propertyHierarchy: Map<Iri, List<Iri>>,   // property -> superproperties
    val equivalentClasses: Map<Iri, Set<Iri>>,
    val disjointClasses: Map<Iri, Set<Iri>>
)
```

### **Consistency Checking**

```kotlin
data class ConsistencyResult(
    val isConsistent: Boolean,
    val inconsistencies: List<Inconsistency>,
    val warnings: List<String>,
    val explanations: List<Explanation>
)
```

### **Validation Reports**

```kotlin
data class ValidationReport(
    val isValid: Boolean,
    val violations: List<ValidationViolation>,
    val warnings: List<String>,
    val statistics: ValidationStatistics
)
```

## 🎯 **Provider Capabilities**

Each reasoner provider reports its capabilities:

```kotlin
data class ReasonerCapabilities(
    val supportedTypes: Set<ReasonerType>,
    val supportsIncrementalReasoning: Boolean,
    val supportsCustomRules: Boolean,
    val supportsExplanation: Boolean,
    val supportsConsistencyChecking: Boolean,
    val supportsClassification: Boolean,
    val maxGraphSize: Long,
    val typicalPerformance: PerformanceProfile
)
```

## 🔌 **Service Provider Discovery**

The framework uses Java ServiceLoader for automatic discovery of reasoner providers:

- **Core Module**: `MemoryReasonerProvider` (always available)
- **`jena-reasoning` artifact**: `JenaReasonerProvider` (SPI + direct import)
- **`rdf4j-reasoning` artifact**: `Rdf4jReasonerProvider` (SPI + direct import)

Add **`com.geoknoesis.kastor:jena-reasoning`** / **`com.geoknoesis.kastor:rdf4j-reasoning`** alongside **`com.geoknoesis.kastor:rdf-jena`** / **`rdf-rdf4j`** when you need those providers; they are **not** transitive from the store adapters.

## 📊 **Performance Considerations**

### **Built-in Memory Reasoner**
- **Performance**: Fast (PerformanceProfile.FAST)
- **Memory Usage**: Low
- **Best For**: Small to medium graphs, basic RDFS reasoning

### **Jena Reasoner**
- **Performance**: Medium (PerformanceProfile.MEDIUM)
- **Memory Usage**: Moderate
- **Best For**: Medium to large graphs, OWL reasoning

### **RDF4J Reasoner**
- **Performance**: Fast (PerformanceProfile.FAST)
- **Memory Usage**: Low
- **Best For**: Large graphs, streaming operations

## 🧪 **Testing**

Comprehensive tests are provided:

```kotlin
class BasicReasoningTest {
    @Test
    fun `memory reasoner performs basic RDFS inference`() {
        val graph = createSampleGraph()
        val reasoner = Rdf.reasoner(ReasonerType.RDFS)
        
        val result = reasoner.reason(graph)
        
        assertNotNull(result)
        assertTrue(result.inferredTriples.isNotEmpty())
        assertTrue(result.consistencyCheck.isConsistent)
        assertNotNull(result.classification)
    }
}
```

## 🚀 **Future Enhancements**

The architecture is designed to support:

1. **Advanced Reasoners**: Pellet, HermiT, FaCT++
2. **Streaming Reasoning**: For very large graphs
3. **Incremental Reasoning**: Only reason about new/changed triples
4. **Custom Rules**: User-defined inference rules
5. **Explanation Generation**: Why certain triples were inferred
6. **Performance Optimization**: Caching, parallel processing

## 📝 **Migration Guide**

To add reasoning to existing Kastor applications:

1. **Add Dependencies**:
   ```kotlin
   implementation(project(":rdf:core"))
   implementation(project(":rdf:reasoning"))
   implementation(project(":rdf:jena"))
   implementation(project(":rdf:jena-reasoning"))   // Jena-backed RdfReasonerProvider
   implementation(project(":rdf:rdf4j"))
   implementation(project(":rdf:rdf4j-reasoning")) // RDF4J-backed RdfReasonerProvider
   ```

   For Maven coordinates, use the [**kastor-bom**](https://github.com/geoknoesis/kastor/blob/main/bom/build.gradle.kts); optional reasoners are published as **`jena-reasoning`** and **`rdf4j-reasoning`**.

2. **Use Reasoning**:
   ```kotlin
   val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
   val result = reasoner.reason(yourGraph)
   ```

3. **Handle Results**:
   ```kotlin
   // Add inferred triples to your repository
   result.inferredTriples.forEach { triple ->
       repository.add(triple)
   }
   
   // Check consistency
   if (!result.consistencyCheck.isConsistent) {
       // Handle inconsistencies
   }
   ```

The reasoning framework integrates seamlessly with the existing Kastor RDF API, providing powerful inference capabilities while maintaining the same clean, DSL-based approach that developers already know and love.



