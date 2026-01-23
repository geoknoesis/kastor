# Kastor SDK: Comprehensive Review for Real-World Adoption

**Review Date:** 2024  
**SDK Name:** Kastor  
**Version:** 0.1.0  
**Reviewer:** Senior Kotlin Platform Engineer + Semantic Web Architect

---

## 1. Executive Summary

### Overall Assessment: **8.2/10** - Strong Foundation with Critical Gaps

**Strengths:**
- ✅ Excellent sealed type hierarchy for RDF terms (Iri, BlankNode, Literal, TripleTerm)
- ✅ Provider-agnostic architecture (Jena, RDF4J, Memory, SPARQL)
- ✅ Natural language DSL for graph building (`person has name with "Alice"`)
- ✅ Code generation from SHACL/JSON-LD with type-safe interfaces
- ✅ Strong separation: domain-first, RDF as side-channel
- ✅ SHACL validation with structured reports
- ✅ Transaction support across providers

**Critical Gaps:**
- ❌ **P0:** Parsing errors lack line/column context (blocks debugging)
- ❌ **P0:** No streaming parsing API (loads entire file into memory)
- ❌ **P0:** Missing "hello world" runnable examples in root
- ❌ **P1:** Codegen output stability not guaranteed (non-deterministic ordering risk)
- ❌ **P1:** JSON-LD framing/compaction behavior not clearly documented
- ❌ **P1:** BNode identity scope rules not explicitly documented

**Adoption Readiness:** **7/10** - Good for RDF experts, challenging for newcomers

**Maintenance Readiness:** **9/10** - Well-structured, but needs error handling improvements

---

## 2. Conceptual Model

### 2.1 Core Nouns (Types)

**RDF Term Hierarchy:**
```kotlin
sealed interface RdfTerm
sealed interface RdfResource : RdfTerm
  - Iri(value: String) // value class
  - BlankNode(id: String) // value class
  - TripleTerm(triple: RdfTriple) // RDF-star
- Literal(lexical: String, lang: String?, datatype: Iri?)
```

**Graph & Dataset:**
- `RdfGraph` (read-only): `hasTriple()`, `getTriples()`, `size()`
- `GraphEditor` (mutable): `addTriple()`, `removeTriple()`, `clear()`
- `MutableRdfGraph` = `RdfGraph` + `GraphEditor`
- `Dataset` (SPARQL dataset with default + named graphs)
- `RdfRepository` extends `Dataset` + `SparqlMutable`

**Query & Results:**
- `SparqlSelect`, `SparqlAsk`, `SparqlConstruct`, `SparqlDescribe`, `UpdateQuery`
- `SparqlQueryResult` (iterable `BindingSet` rows)
- `BindingSet`: `get(name)`, `getString()`, `getInt()`, etc.

**Code Generation:**
- Generated interfaces (pure Kotlin, no RDF deps)
- Generated wrappers (RDF-backed implementations)
- Generated DSL builders
- Generated vocabulary constants

**Validation:**
- `ValidationReport` (violations, warnings, statistics)
- `ShaclViolation` (focusNode, shapeIri, path, message, severity)
- `ValidationResult` (sealed: `Ok` | `Violations`)

### 2.2 Core Verbs (Operations)

**Graph Building:**
- `repo.add { ... }` - DSL builder
- `graph.addTriple()`, `graph.removeTriple()`
- Natural language: `person has name with "Alice"`, `person is "foaf:Person"`

**Parsing/Serialization:**
- `Rdf.parse(content, format)` - from string
- `Rdf.parseFromFile(path, format)` - from file
- `Rdf.parseFromUrl(url, format)` - from URL
- `Rdf.parseFromInputStream(stream, format)` - from stream
- Serialization: provider-dependent (no unified API visible)

**Querying:**
- `repo.select(query)` - SPARQL SELECT
- `repo.ask(query)` - SPARQL ASK
- `repo.construct(query)` - SPARQL CONSTRUCT
- `repo.describe(query)` - SPARQL DESCRIBE
- `repo.update(query)` - SPARQL UPDATE
- Type-safe DSL: `select("name") { where { ... } }`

**Transactions:**
- `repo.transaction { ... }` - read-write
- `repo.readTransaction { ... }` - read-only

**Code Generation:**
- KSP processor: `@GenerateFromOntology`, `@GenerateInstanceDsl`
- Gradle plugin: `generateVocabulary`, `generateInterfaces`
- Generates: interfaces, wrappers, DSL, vocabulary constants

**Validation:**
- `validator.validate(dataGraph, shapesGraph)` - returns `ValidationReport`
- `rdfRef.asValidatedType(validation)` - validate during materialization
- `person.asRdf().validateOrThrow()` - validate after materialization

### 2.3 Golden Paths

**GP1: Create RDF, Serialize** ✅ **8/10**
```kotlin
val repo = Rdf.memory()
repo.add {
    val person = iri("http://example.org/alice")
    person has FOAF.name with "Alice"
    person has FOAF.age with 30
}
// Serialization: provider-specific, not unified
```
**Gap:** No unified serialization API; must use provider-specific methods.

**GP2: Parse RDF, Query** ✅ **7/10**
```kotlin
val graph = Rdf.parseFromFile("data.ttl", RdfFormat.TURTLE)
repo.addTriples(graph.getTriples())
val results = repo.select(SparqlSelectQuery("SELECT ?name WHERE { ... }"))
```
**Gap:** Parsing errors lack line/column context; no streaming parse.

**GP3: Code Generation** ✅ **8/10**
```kotlin
// KSP annotation
@GenerateFromOntology(
    shacl = "shapes.ttl",
    context = "context.jsonld",
    packageName = "com.example"
)
interface Person { ... }
```
**Gap:** Output stability not guaranteed; no incremental build docs.

**GP4: SHACL Validation** ✅ **9/10**
```kotlin
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)
if (!report.isValid) {
    report.violations.forEach { println(it.message) }
}
```
**Strong:** Well-structured reports with focus node, path, severity.

**GP5: Store/Transaction** ✅ **8/10**
```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "/data/tdb2"
}
repo.transaction {
    // ACID operations
}
```
**Gap:** Streaming for large graphs not clearly documented.

---

## 3. Scorecard

| Category | Score | Rationale |
|----------|-------|-----------|
| **A. Time-to-First-Success** | **6/10** | No runnable "hello world" in root; examples require build setup; codegen setup complex |
| **B. RDF Correctness & Spec Alignment** | **9/10** | Sealed term hierarchy correct; IRI vs BNode vs Literal properly modeled; prefix handling good; BNode scope not explicitly documented |
| **C. Kotlin Idioms & Type Safety** | **9/10** | Excellent sealed interfaces; value classes for Iri/BlankNode; nullability correct; avoids stringly-typed APIs; missing inline/reified in some places |
| **D. API Ergonomics** | **8/10** | Natural language DSL discoverable; SPARQL DSL type-safe; some overload ambiguity in DSL; clear high-level vs low-level separation |
| **E. Performance & Memory** | **6/10** | Parsing loads entire file (`readBytes()`); no streaming Sequence/Flow API visible; no interning strategy documented; no benchmarks |
| **F. Error Model & Debuggability** | **6/10** | Codegen has `ErrorContext` (file, line, property); parsing errors lack line/column; query errors include query string; sealed error hierarchy exists but incomplete |
| **G. Code Generation Quality** | **8/10** | Generated Kotlin idiomatic; deterministic output not guaranteed; naming predictable; incremental builds unclear; mapping strategy explicit; SHACL semantics well-supported |
| **H. Schema & Validation Integration** | **9/10** | Shapes → Kotlin types via codegen; validation reports usable (focus node, path, message, severity); validate on write vs demand supported; runtime validation aligns with generated constraints |
| **I. Observability & Tooling** | **6/10** | SLF4J logging hooks; no parse/query trace visible; no debug mode for prefix expansion; Gradle plugin exists but UX unclear |
| **J. Versioning & Evolvability** | **7/10** | No backward compat policy visible; no deprecation strategy; vocab evolution handling unclear; generated API stability across regeneration not guaranteed |

**Weighted Average: 7.4/10**

---

## 4. Detailed Findings

### A. Time-to-First-Success (TTFS) for RDF + Codegen

**Score: 6/10**

**Evidence:**
- README has examples but no runnable "hello world" script in root
- Examples require: `./gradlew :examples:dcat-us:run` (complex path)
- Codegen setup requires KSP configuration + annotations + ontology files
- No single-file "hello graph" example

**Fixes:**
1. **Add `examples/hello-world.kt`** in root with `fun main()` that creates graph, serializes, queries
2. **Add `examples/hello-codegen.kt`** showing minimal codegen setup
3. **Add Gradle task** `./gradlew helloWorld` that runs examples
4. **Simplify codegen quickstart:** single annotation + one ontology file

**Before/After:**
```kotlin
// BEFORE: User must navigate examples/ directory
cd examples/dcat-us
../../gradlew run

// AFTER: Single command from root
./gradlew helloWorld
// Or: kotlin examples/hello-world.kt
```

---

### B. RDF Correctness & Spec Alignment

**Score: 9/10**

**Evidence:**
- ✅ Sealed hierarchy: `RdfTerm` → `RdfResource` (Iri, BlankNode, TripleTerm) | `Literal`
- ✅ IRI validation in `Iri(value: String)`
- ✅ Literal equality considers datatype + lang tag
- ✅ Prefix map handling with built-in prefixes (rdf, rdfs, owl, sh, xsd)
- ✅ Dataset vs Graph semantics: `Dataset` interface separate from `RdfGraph`

**Gaps:**
1. **BNode identity scope:** Not explicitly documented (global vs per-parse)
2. **JSON-LD compaction/framing:** Behavior not clearly documented (lossless claim?)

**Fixes:**
1. **Document BNode scope:** Add KDoc to `BlankNode` explaining identity rules
2. **Document JSON-LD behavior:** Add section on compaction/framing limitations
3. **Add canonicalization docs:** When triples are considered equal

---

### C. Kotlin Idioms & Type Safety

**Score: 9/10**

**Evidence:**
- ✅ Sealed interfaces: `RdfTerm`, `RdfResource`, `ValidationResult`
- ✅ Value classes: `Iri`, `BlankNode` (minimal allocation)
- ✅ Nullability: `Literal.lang: String?`, `Literal.datatype: Iri?`
- ✅ Avoids stringly-typed: `Iri` not `String` for IRIs
- ✅ Extension functions: `person has name with "Alice"`

**Gaps:**
1. **Missing inline/reified:** `materialize<T>()` could use `inline fun <reified T>`
2. **Some stringly-typed:** Format names as strings (`"TURTLE"` vs `RdfFormat.TURTLE`)

**Fixes:**
1. **Add inline reified:** `inline fun <reified T> RdfRef.asType(): T`
2. **Prefer enum over string:** `RdfFormat` enum exists but string overloads still present

---

### D. API Ergonomics (Graph building + querying)

**Score: 8/10**

**Evidence:**
- ✅ Natural language DSL: `person has name with "Alice"`, `person is "foaf:Person"`
- ✅ SPARQL DSL: `select("name") { where { triple(...) } }`
- ✅ Smart QName detection: auto-resolves `"foaf:Person"` to IRI
- ✅ Clear separation: `RdfGraph` (read) vs `GraphEditor` (write)

**Gaps:**
1. **Overload ambiguity:** Multiple ways to add triples (DSL vs direct)
2. **DSL scope leaks:** `@DslMarker` exists but not consistently applied

**Fixes:**
1. **Consistent DSL markers:** Apply `@DslMarker` to all DSL builders
2. **Document DSL vs direct:** When to use DSL vs `addTriple()` directly

---

### E. Performance & Memory for RDF Workloads

**Score: 6/10**

**Evidence:**
- ❌ Parsing loads entire file: `inputStream.readBytes()` in `parseFromInputStream()`
- ❌ No streaming API visible: `getTriples()` returns `List<RdfTriple>`
- ❌ No Sequence/Flow support for parsing
- ❌ No interning strategy documented

**Fixes:**
1. **Add streaming parse:** `fun parseStreaming(inputStream: InputStream, format: RdfFormat): Sequence<RdfTriple>`
2. **Add Sequence support:** `fun RdfGraph.getTriplesSequence(): Sequence<RdfTriple>`
3. **Document memory strategy:** When to use streaming vs in-memory
4. **Add benchmarks:** Performance guide with large dataset examples

**Before/After:**
```kotlin
// BEFORE: Loads entire file
val graph = Rdf.parseFromFile("large.ttl") // OOM risk

// AFTER: Streaming
Rdf.parseStreaming(File("large.ttl").inputStream(), RdfFormat.TURTLE)
    .forEach { triple -> process(triple) } // Memory efficient
```

---

### F. Error Model & Debuggability

**Score: 6/10**

**Evidence:**
- ✅ Codegen has `ErrorContext`: `file`, `line`, `property`, `shape`, `classIri`
- ✅ Query errors include query string: `RdfQueryException.query`
- ✅ Sealed error hierarchy: `RdfException` sealed class
- ❌ Parsing errors lack line/column: `RdfFormatException` doesn't include position
- ❌ No error codes for programmatic handling

**Fixes:**
1. **Add parsing error context:**
```kotlin
data class ParseError(
    val message: String,
    val line: Int?,
    val column: Int?,
    val snippet: String?,
    val format: String
)
```
2. **Add error codes:** `enum class RdfErrorCode { PARSE_ERROR, QUERY_ERROR, ... }`
3. **Enhance RdfFormatException:** Include line/column when available from provider

---

### G. Code Generation Quality

**Score: 8/10**

**Evidence:**
- ✅ Generated Kotlin idiomatic: interfaces, data classes, builders
- ✅ Naming predictable: `NamingUtils.extractInterfaceName()`
- ✅ Mapping strategy explicit: `TypeMapper.toKotlinType()`
- ✅ SHACL semantics well-supported: constraints → Kotlin types
- ⚠️ Deterministic output: Not guaranteed (ordering risk in maps/sets)
- ⚠️ Incremental builds: KSP supports it but not explicitly documented

**Fixes:**
1. **Guarantee deterministic output:** Sort shapes/properties before generation
2. **Document incremental builds:** KSP task inputs/outputs
3. **Add stability tests:** Regenerate twice, compare outputs
4. **Document collision strategy:** What happens when two classes have same name?

---

### H. Schema & Validation Integration

**Score: 9/10**

**Evidence:**
- ✅ Shapes → Kotlin types: Codegen from SHACL shapes
- ✅ Validation reports usable: `ValidationReport` with `violations`, `warnings`, `statistics`
- ✅ Focus node, path, message, severity: `ShaclViolation` has all fields
- ✅ Validate on write vs demand: `asValidatedType()` vs `validateOrThrow()`
- ✅ Runtime validation aligns: Generated constraints match SHACL

**Minor Gap:**
- Validation performance not documented for large graphs

**Fix:**
- Add validation performance guide: When to validate on write vs demand

---

### I. Observability & Tooling

**Score: 6/10**

**Evidence:**
- ✅ SLF4J logging: `KSPLogger` in processor
- ✅ Gradle plugin: `kastor-gen` plugin exists
- ❌ No parse/query trace: No debug mode visible
- ❌ No prefix expansion debug: Can't see how QNames resolve
- ❌ No CLI tool: Only Gradle plugin

**Fixes:**
1. **Add debug mode:** `Rdf.debug { showPrefixExpansion = true }`
2. **Add query trace:** Log SPARQL queries with bindings
3. **Add CLI tool:** `kastor-cli generate --shacl shapes.ttl --context context.jsonld`

---

### J. Versioning & Evolvability

**Score: 7/10**

**Evidence:**
- ⚠️ No backward compat policy: No semver strategy visible
- ⚠️ No deprecation strategy: No `@Deprecated` usage pattern
- ⚠️ Vocab evolution: Handling of new/renamed/deprecated terms unclear
- ⚠️ Generated API stability: Not guaranteed across regeneration

**Fixes:**
1. **Add versioning policy:** Semver with backward compat guarantees
2. **Add deprecation strategy:** How to deprecate APIs
3. **Document vocab evolution:** How codegen handles ontology changes
4. **Guarantee generated API stability:** Same ontology → same code (deterministic)

---

## 5. RDF SDK Trap Checklist

| Trap | Status | Notes |
|------|--------|-------|
| **Treating IRIs as raw strings** | ✅ **PASS** | `Iri` value class used throughout |
| **BNode identity mistakes** | ⚠️ **WARN** | Scope rules not explicitly documented |
| **Literal equality rules ignored** | ✅ **PASS** | Datatype + lang tag considered |
| **Prefix map confusion** | ✅ **PASS** | Built-in prefixes + explicit prefix maps |
| **JSON-LD compaction/framing lossless claim** | ⚠️ **WARN** | Behavior not clearly documented |
| **Overly clever DSL hiding semantics** | ✅ **PASS** | DSL is explicit, can access low-level |
| **Codegen produces "ontology-shaped classes" misleading about reasoning** | ✅ **PASS** | Generated interfaces are pure Kotlin, reasoning explicit |
| **Massive API surface from per-vocab boilerplate** | ✅ **PASS** | Codegen reduces boilerplate, core API small |

**Summary:** 6/8 pass, 2 warnings (BNode scope, JSON-LD behavior)

---

## 6. Prioritized Roadmap

### P0: Must-Fix for Adoption (Top 3-5)

#### P0.1: Add Parsing Error Context (Line/Column)
**Impact:** Blocks debugging of malformed RDF files  
**Effort:** Medium (2-3 days)  
**Before:**
```kotlin
catch (e: Exception) {
    throw RdfFormatException("Failed to parse RDF: ${e.message}", e)
}
```
**After:**
```kotlin
data class ParseError(
    val message: String,
    val line: Int?,
    val column: Int?,
    val snippet: String?,
    val format: String,
    val cause: Throwable?
)

sealed class RdfFormatException : RdfException {
    data class ParseError(
        val parseError: ParseError
    ) : RdfFormatException(parseError.message, parseError.cause)
}
```

#### P0.2: Add Streaming Parsing API
**Impact:** Enables processing large files without OOM  
**Effort:** High (3-5 days)  
**Before:**
```kotlin
fun parseFromInputStream(inputStream: InputStream, format: String): MutableRdfGraph {
    val data = inputStream.readBytes() // Loads entire file
    return provider.parseGraph(data.inputStream(), format)
}
```
**After:**
```kotlin
fun parseStreaming(
    inputStream: InputStream,
    format: RdfFormat
): Sequence<RdfTriple> {
    return provider.parseStreaming(inputStream, format)
}

fun parseFromInputStream(
    inputStream: InputStream,
    format: String
): MutableRdfGraph {
    // Keep for backward compat, but document memory implications
    return parseStreaming(inputStream, RdfFormat.fromString(format))
        .toList()
        .let { triples -> MutableRdfGraphImpl(triples) }
}
```

#### P0.3: Add Runnable "Hello World" Examples
**Impact:** Reduces TTFS from hours to minutes  
**Effort:** Low (1 day)  
**Create:**
- `examples/hello-world.kt` - Create graph, serialize, query
- `examples/hello-codegen.kt` - Minimal codegen example
- `./gradlew helloWorld` task

#### P0.4: Guarantee Codegen Deterministic Output
**Impact:** Prevents build instability  
**Effort:** Medium (2 days)  
**Fix:**
```kotlin
// BEFORE: Non-deterministic
ontologyModel.shapes.forEach { ... } // Map iteration order

// AFTER: Deterministic
ontologyModel.shapes
    .sortedBy { it.shapeIri } // Stable ordering
    .forEach { ... }
```

#### P0.5: Document BNode Identity Scope
**Impact:** Prevents subtle bugs  
**Effort:** Low (0.5 days)  
**Add KDoc:**
```kotlin
/**
 * Represents a blank node (anonymous resource).
 * 
 * **Identity Scope:** Blank nodes are scoped to the graph/repository where they are created.
 * Two blank nodes with the same ID from different parse operations are NOT equal.
 * 
 * @property id The identifier for the blank node (scoped to its graph)
 */
data class BlankNode(val id: String) : RdfResource
```

---

### P1: Should-Fix

1. **Add unified serialization API** (currently provider-specific)
2. **Add error codes** for programmatic error handling
3. **Document JSON-LD compaction/framing behavior** (lossless claim?)
4. **Add debug mode** for prefix expansion and query tracing
5. **Add versioning policy** (semver, backward compat guarantees)
6. **Add performance benchmarks** for large datasets
7. **Document incremental builds** (KSP task inputs/outputs)

---

### P2: Nice-to-Have

1. **Add CLI tool** for codegen (beyond Gradle plugin)
2. **Add inline reified functions** (`inline fun <reified T> asType()`)
3. **Add validation performance guide** (when to validate on write vs demand)
4. **Add collision strategy docs** (what happens when two classes have same name?)
5. **Add deprecation strategy** (how to deprecate APIs)

---

## 7. API Sketches (Kotlin Snippets)

### P0.1: Enhanced Parsing Error Context

```kotlin
// Enhanced error model
data class ParseError(
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val snippet: String? = null,
    val format: String,
    val cause: Throwable? = null
) {
    override fun toString(): String = buildString {
        append("Parse error in $format")
        if (line != null) append(" at line $line")
        if (column != null) append(", column $column")
        append(": $message")
        if (snippet != null) append("\nSnippet: $snippet")
    }
}

sealed class RdfFormatException(
    message: String,
    cause: Throwable? = null
) : RdfException(message, cause) {
    data class ParseError(
        val parseError: ParseError
    ) : RdfFormatException(parseError.toString(), parseError.cause)
    
    data class UnsupportedFormat(
        val format: String,
        val availableFormats: List<String>
    ) : RdfFormatException(
        "Unsupported format: $format. Available: ${availableFormats.joinToString()}"
    )
}
```

### P0.2: Streaming Parsing API

```kotlin
// Streaming parse API
fun Rdf.parseStreaming(
    inputStream: InputStream,
    format: RdfFormat,
    bufferSize: Int = 8192
): Sequence<RdfTriple> = sequence {
    val formatEnum = RdfFormat.fromStringOrThrow(format.formatName)
    val providers = RdfProviderRegistry.discoverProviders()
    
    val provider = providers.firstOrNull { it.supportsFormat(formatEnum.formatName) }
        ?: throw RdfFormatException.UnsupportedFormat(
            format.formatName,
            providers.flatMap { it.supportedFormats }
        )
    
    provider.parseStreaming(inputStream, formatEnum.formatName).forEach { triple ->
        yield(triple)
    }
}

// Usage
Rdf.parseStreaming(File("large.ttl").inputStream(), RdfFormat.TURTLE)
    .chunked(1000) // Process in batches
    .forEach { batch ->
        repo.addTriples(batch)
    }
```

### P0.3: Hello World Examples

```kotlin
// examples/hello-world.kt
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

fun main() {
    // Create repository
    val repo = Rdf.memory()
    
    // Add RDF data
    repo.add {
        prefixes {
            "foaf" to "http://xmlns.com/foaf/0.1/"
        }
        
        val alice = iri("http://example.org/alice")
        alice has FOAF.name with "Alice"
        alice has FOAF.age with 30
        alice is FOAF.Person
    }
    
    // Query
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?name ?age WHERE {
            ?person a <http://xmlns.com/foaf/0.1/Person> ;
                    <http://xmlns.com/foaf/0.1/name> ?name ;
                    <http://xmlns.com/foaf/0.1/age> ?age .
        }
    """))
    
    results.forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
    
    // Serialize
    val turtle = repo.defaultGraph.serialize(RdfFormat.TURTLE)
    println(turtle)
}
```

---

## 8. Conclusion

**Kastor is a well-architected RDF SDK with strong foundations**, but has critical gaps that block real-world adoption:

1. **Parsing errors lack context** - Makes debugging malformed RDF nearly impossible
2. **No streaming API** - Can't handle large files without OOM
3. **Missing "hello world"** - High barrier to entry for newcomers
4. **Codegen stability** - Non-deterministic output risks build instability
5. **Documentation gaps** - BNode scope, JSON-LD behavior unclear

**With P0 fixes implemented, Kastor would score 9.0/10** and be ready for production adoption.

**Estimated effort for P0 fixes:** 1-2 weeks  
**Expected outcome:** Production-ready SDK with excellent developer experience

---

**Review completed by:** Senior Kotlin Platform Engineer + Semantic Web Architect  
**Date:** 2024

