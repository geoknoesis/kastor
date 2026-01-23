# Code Review: Dataset Implementation

## Overall Assessment: ⭐⭐⭐⭐ (4/5)

The Dataset implementation is well-designed and follows industry best practices. However, there are several areas for improvement in code quality, performance, and consistency.

---

## ✅ Strengths

### 1. **Excellent Design Patterns**
- ✅ Proper use of delegation pattern (`GraphRef` by `RdfGraph`)
- ✅ Clean separation: `Dataset` (read-only) vs `RdfRepository` (mutable)
- ✅ Builder pattern for fluent API
- ✅ Interface hierarchy: `SparqlRepository` → `Dataset` → `RdfRepository`

### 2. **SPARQL 1.1 Compliance**
- ✅ Correctly implements dataset semantics
- ✅ Proper FROM/FROM NAMED clause handling
- ✅ Query rewriting for optimization

### 3. **Good Documentation**
- ✅ Comprehensive KDoc comments
- ✅ Clear examples in documentation
- ✅ References to SPARQL 1.1 specification

---

## ⚠️ Issues & Recommendations

### 🔴 Critical Issues

#### 1. **Code Duplication in Materialized Execution**
**Location:** `DatasetImpl.kt` lines 246-308

**Problem:** The four materialized execution methods (`executeOnMaterializedUnion`, `executeAskOnMaterializedUnion`, etc.) have nearly identical code - only the query execution differs.

**Impact:** 
- Maintenance burden (fixes need to be applied 4 times)
- Risk of inconsistencies
- Code bloat

**Recommendation:**
```kotlin
private fun <T> executeOnMaterializedUnion(
    query: SparqlQuery,
    execute: (RdfRepository, SparqlQuery) -> T
): T {
    val unionRepo = Rdf.memory()
    try {
        materializeGraphs(unionRepo)
        return execute(unionRepo, query)
    } finally {
        unionRepo.close()
    }
}

private fun materializeGraphs(repo: RdfRepository) {
    defaultGraphRefs.forEach { ref ->
        repo.editDefaultGraph().addTriples(ref.getReferencedGraph().getTriples())
    }
    namedGraphRefs.forEach { (name, ref) ->
        val targetGraph = repo.createGraph(name)
        (targetGraph as MutableRdfGraph).addTriples(ref.getReferencedGraph().getTriples())
    }
}
```

#### 2. **Potential Performance Issue: `namedGraphs` in RdfRepository**
**Location:** `RdfCore.kt` line 600-601

**Problem:** 
```kotlin
override val namedGraphs: Map<Iri, RdfGraph>
    get() = listGraphs().associateWith { getGraph(it) }
```

This creates a new map and potentially new graph instances on every access. For repositories with many graphs, this is expensive.

**Impact:** O(n) complexity on every access, unnecessary object creation

**Recommendation:** Cache the result or make it lazy:
```kotlin
override val namedGraphs: Map<Iri, RdfGraph> by lazy {
    listGraphs().associateWith { getGraph(it) }
}
```

#### 3. **Inefficient Materialization**
**Location:** `DatasetImpl.kt` lines 249-255

**Problem:** Using `getTriples()` loads all triples into memory at once. For large graphs, this can cause OOM.

**Impact:** Memory issues with large datasets

**Recommendation:** Use streaming approach:
```kotlin
defaultGraphRefs.forEach { ref ->
    ref.getReferencedGraph().getTriplesSequence().forEach { triple ->
        unionRepo.editDefaultGraph().addTriple(triple)
    }
}
```

---

### 🟡 Medium Priority Issues

#### 4. **Inconsistent Error Message**
**Location:** `DatasetImpl.kt` line 88

**Problem:** Error message says "SPARQL Dataset" but interface is now just "Dataset"

**Fix:**
```kotlin
throw UnsupportedOperationException("Dataset is read-only. Use RdfRepository for updates.")
```

#### 5. **Query Rewriting Edge Cases**
**Location:** `DatasetImpl.kt` lines 155-157

**Problem:** The regex check for existing FROM clauses might miss some cases:
- Subqueries with FROM clauses
- Comments containing "FROM"
- Case variations

**Recommendation:** Use a proper SPARQL parser or more robust regex:
```kotlin
// More robust check - look for FROM at start of line (after whitespace)
if (queryText.contains(Regex("^\\s*FROM\\s+", RegexOption.MULTILINE or RegexOption.IGNORE_CASE))) {
    return query
}
```

#### 6. **Missing Validation in Builder**
**Location:** `Dataset.kt` line 180

**Problem:** Only checks if default graphs are empty, but doesn't validate:
- Duplicate graph names in namedGraphs
- Null graphs
- Invalid IRIs

**Recommendation:**
```kotlin
fun build(): Dataset {
    require(defaultGraphs.isNotEmpty()) { 
        "At least one default graph is required for a dataset" 
    }
    require(defaultGraphs.none { it == null }) {
        "Default graphs cannot contain null"
    }
    // Check for duplicate named graph names
    require(namedGraphs.size == namedGraphs.keys.distinct().size) {
        "Duplicate named graph names are not allowed"
    }
    return DatasetImpl(...)
}
```

#### 7. **GraphRef.hasSourceTracking() Logic**
**Location:** `GraphRef.kt` line 48

**Problem:** 
```kotlin
fun hasSourceTracking(): Boolean = sourceRepository != null
```

But `sourceGraphName` can be null even when we have a repository (for default graph). The name is misleading - it should check if we have enough info for optimization.

**Recommendation:** Rename or clarify:
```kotlin
fun hasSourceTracking(): Boolean = sourceRepository != null
// Or more explicit:
fun canOptimize(): Boolean = sourceRepository != null
```

#### 8. **OptimizedUnionGraph Query Building**
**Location:** `DatasetImpl.kt` lines 323-325

**Problem:** FROM clauses are built incorrectly when there are multiple graphs:
```kotlin
val fromClauses = graphNames.joinToString("\n") { name ->
    if (name != null) "FROM <${name.value}>" else ""
}.trim()
```

This creates empty lines for default graphs. Should be:
```kotlin
val fromClauses = graphNames
    .filterNotNull()
    .joinToString("\n") { "FROM <${it.value}>" }
```

---

### 🟢 Minor Issues & Improvements

#### 9. **Documentation Inconsistency**
**Location:** `Dataset.kt` line 40

**Problem:** Says "@see SparqlRepository" but should reference `RdfRepository` for mutation operations.

**Fix:**
```kotlin
 * @see RdfRepository for mutable dataset operations
 * @see SparqlRepository for minimal query interface
```

#### 10. **Missing Null Safety**
**Location:** `DatasetImpl.kt` line 108

**Problem:** Uses `!!` operator:
```kotlin
.groupBy({ it.sourceRepository!! }, { it.sourceGraphName })
```

**Recommendation:** Use `filterNotNull()`:
```kotlin
.filter { it.hasSourceTracking() && it.sourceRepository != null }
.groupBy({ it.sourceRepository!! }, { it.sourceGraphName })
```

#### 11. **Inefficient List Creation**
**Location:** `DatasetImpl.kt` line 69

**Problem:**
```kotlin
override fun listNamedGraphs(): List<Iri> = namedGraphRefs.keys.toList()
```

`keys` is already a collection, `.toList()` is redundant if `keys` is already a list/set.

**Fix:**
```kotlin
override fun listNamedGraphs(): List<Iri> = namedGraphRefs.keys.toList() // Actually fine, keys is Set
```

#### 12. **Type Casting in OptimizedUnionGraph**
**Location:** `DatasetImpl.kt` lines 352-354

**Problem:** Unsafe casts:
```kotlin
binding.get("s") as RdfResource,
binding.get("p") as Iri,
binding.get("o") as RdfTerm
```

**Recommendation:** Add validation or use safe casts:
```kotlin
val s = binding.get("s") as? RdfResource ?: return@map null
val p = binding.get("p") as? Iri ?: return@map null
val o = binding.get("o") as? RdfTerm ?: return@map null
RdfTriple(s, p, o)
```

#### 13. **Missing Edge Case Handling**
**Location:** `DatasetImpl.kt` line 376

**Problem:** Count query might fail if repository doesn't support COUNT aggregation.

**Recommendation:** Add fallback:
```kotlin
override fun size(): Int {
    return try {
        // Try optimized COUNT query
        val fromClauses = ...
        val query = "SELECT (COUNT(*) AS ?count) { ... }"
        val result = repository.select(SparqlSelectQuery(query))
        val count = result.firstOrNull()?.get("count") as? TypedLiteral
        count?.lexical?.toIntOrNull() ?: getTriples().size
    } catch (e: Exception) {
        // Fallback to materialization
        getTriples().size
    }
}
```

---

## 📋 API Design Issues

#### 14. **Builder API Inconsistency**
**Location:** `Dataset.kt` lines 133-171

**Problem:** Two overloads of `defaultGraph()` and `namedGraph()` with different parameter patterns. Could be confusing.

**Current:**
- `defaultGraph(graph: RdfGraph)`
- `defaultGraph(repository: RdfRepository)`
- `namedGraph(name: Iri, graph: RdfGraph)`
- `namedGraph(name: Iri, repository: RdfRepository, sourceGraphName: Iri? = null)`

**Recommendation:** Consider more explicit naming:
```kotlin
fun defaultGraph(graph: RdfGraph): DatasetBuilder
fun defaultGraphFrom(repository: RdfRepository): DatasetBuilder
fun namedGraph(name: Iri, graph: RdfGraph): DatasetBuilder
fun namedGraphFrom(name: Iri, repository: RdfRepository, sourceGraphName: Iri? = null): DatasetBuilder
```

#### 15. **Missing Convenience Methods**
**Location:** `Dataset.kt`

**Recommendation:** Add convenience methods:
```kotlin
// In DatasetBuilder
fun defaultGraphs(vararg graphs: RdfGraph): DatasetBuilder {
    graphs.forEach { defaultGraph(it) }
    return this
}

fun namedGraphs(vararg pairs: Pair<Iri, RdfGraph>): DatasetBuilder {
    pairs.forEach { (name, graph) -> namedGraph(name, graph) }
    return this
}
```

---

## 🎯 Best Practices

### 16. **Thread Safety**
**Location:** `DatasetImpl.kt`

**Issue:** No thread-safety guarantees. Multiple threads accessing the same dataset could cause issues.

**Recommendation:** Document thread-safety requirements or add synchronization if needed.

### 17. **Resource Management**
**Location:** `DatasetImpl.kt` lines 91-100

**Good:** Properly closes resources. However, consider using `use()` for automatic resource management:
```kotlin
override fun close() {
    defaultGraphRefs.forEach { 
        it.getReferencedGraph().takeIf { it is Closeable }?.let { (it as Closeable).close() }
    }
    // Or use a helper extension
}
```

### 18. **Error Handling**
**Location:** Throughout

**Issue:** Some methods throw generic exceptions. Consider more specific exception types:
- `DatasetException` for dataset-specific errors
- `OptimizationException` for optimization failures

---

## 📊 Performance Considerations

### 19. **Lazy Evaluation**
**Location:** `DatasetImpl.kt`

**Good:** Uses `lazy` for expensive computations. However:
- `defaultGraph` is lazy but `defaultGraphs` and `namedGraphs` are not
- Consider making `namedGraphs` lazy if it's expensive

### 20. **Query Rewriting Performance**
**Location:** `DatasetImpl.kt` line 200

**Issue:** Regex replacement on every query. Consider caching rewritten queries or using a proper SPARQL parser.

---

## 🔍 Code Quality

### 21. **Magic Strings**
**Location:** `DatasetImpl.kt` lines 328-332, 342-346, etc.

**Issue:** SPARQL query strings are hardcoded.

**Recommendation:** Extract to constants or use a query builder:
```kotlin
private object QueryTemplates {
    const val ASK_TEMPLATE = """
        ASK {
            %s
            <%s> <%s> %s .
        }
    """.trimIndent()
}
```

### 22. **Complexity**
**Location:** `DatasetImpl.kt` method `rewriteQueryWithFromClauses`

**Issue:** Method is doing too much (building clauses, inserting, type conversion).

**Recommendation:** Split into smaller methods:
```kotlin
private fun buildFromClauses(group: RepositoryGroup): String
private fun buildFromNamedClauses(group: RepositoryGroup): String
private fun insertDatasetClauses(query: String, clauses: String): String
```

---

## 🔴 Critical: Duplicate Files

**Location:** `rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/`

**Problem:** Both `SparqlDataset.kt` and `Dataset.kt` exist (same content). Same for `SparqlDatasetImpl.kt` and `DatasetImpl.kt`.

**Impact:** 
- Confusion about which file to use
- Potential compilation issues
- Maintenance burden

**Fix:** Delete the old `SparqlDataset*.kt` files:
```bash
rm rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/SparqlDataset.kt
rm rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/SparqlDatasetImpl.kt
```

---

## ✅ Summary

**Priority Fixes:**
1. 🔴 **DELETE duplicate files** (`SparqlDataset.kt`, `SparqlDatasetImpl.kt`)
2. 🔴 Remove code duplication in materialized execution
3. 🔴 Fix performance issue with `namedGraphs` in RdfRepository
4. 🔴 Improve materialization to use streaming
5. 🟡 Fix query rewriting edge cases
6. 🟡 Add validation in builder

**Overall:** The implementation is solid but needs refactoring to reduce duplication and improve performance. The design is excellent and follows best practices. **Must delete duplicate files first.**

