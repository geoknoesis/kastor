# SHACL 1.2 SPARQL Custom Functions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** make Kastor a SHACL 1.2 *SPARQLCustomFunctions* processor for functions whose body is a SPARQL node expression (spec §7.2). A `sh:ListParameterExpressionFunction` declared in the shapes graph can then be called from any SHACL-SPARQL query, such as `BIND (ex:spacedConcat('John', 'Doe') AS ?v)`, on Jena and RDF4J alike.

**Architecture:** the SHACL engine compiles each declared function into a provider-neutral `SparqlFunction` (from `sparql-contract`) and sends it with every query in `SandboxedSelectQuery.functions`. Each provider makes those functions visible to that one execution only, so no global registry is ever modified:
- **Jena:** the per-execution `FunctionRegistry` that the sandbox already installs.
- **RDF4J:** a Kastor `EvaluationStrategy` that overrides `prepare(FunctionCall, …)`.

When a function is called, the provider evaluates its arguments and hands them to the function's body. The body runs its own sandboxed SELECT, through a callback, on the dataset of the calling query.

**Tech stack:** Kotlin/JVM, Jena 6.2.0 (`org.apache.jena.sparql.function`), RDF4J 5.3.2 (`org.eclipse.rdf4j.query.algebra.evaluation`), JUnit 5 parameterized over `jena` and `rdf4j`. Run Gradle only through `bash /c/tmp/kastor-gradle.sh`.

**Spec:** https://www.w3.org/TR/shacl12-sparql/ §7 ("Declaring SPARQL Functions based on Node Expressions"). §6 also applies, for select and expr expressions and their scope variables. The W3C cases are in `rdf/shacl/validation/test-data/w3c-shacl12/tests/sparql/functions/`.

## Global Constraints

- §7.3, verbatim:
  - "If a function with the same IRI is already registered as a built-in or other non-custom function, SPARQL engines MUST ignore the attempt to redefine it."
  - "A SPARQL engine MUST NOT modify the registered SPARQL functions while it is executing any SPARQL query."
  - "Where the evaluation has caused an error, the result of the SPARQL function call is also an error, unless the corresponding sh:parameter for the argument has sh:optional true. In that case, the argument will be unbound in the scope."
  - "blank nodes cannot be passed as arguments … an argument that evaluates to a blank node leads to an evaluation failure and thus an error."
  - "If the list of output nodes rs has exactly one member, then return that node. Otherwise, or if the evaluation produces an evaluation failure, the result of the SPARQL function call is an error."
  - "the focusNode passed into a custom SPARQL function based on a node expression is the IRI of the function itself."
- §6, verbatim: "The value of each scope variable is pre-bound as a SPARQL variable with the same name and value. If the name of a variable in scope is not a string literal, use "arg" + str(name)." Argument `i` (from 0) is therefore bound as `$arg<i>`. Bodies pre-bind `this` (the function IRI) and `arg0 … arg<n-1>`. All of these are potentially pre-bound for the Appendix A restriction check.
- Every body query and every nested query is a `SandboxedSelectQuery`. The provider-level refusal of SERVICE and `java:` applies at every nesting depth.
- Nesting is bounded. A call deeper than `MAX_FUNCTION_DEPTH = 16` (a constant in `sparql-contract`) is an error for that call, so recursion terminates. Nested queries never outlive the calling query's timeout.
- Ill-formed declarations throw `ShapeCompileException`. Errors from a function call follow SPARQL error semantics: `BIND` leaves its variable unbound and `FILTER` is false. They never fail validation.
- Public API changes pass `checkLegacyAbi` for `:rdf:sparql-contract`, `:rdf:jena`, `:rdf:rdf4j` and `:rdf:shacl-validation`. Regenerate each dump with `updateLegacyAbi` in the task that changes it.

## Out of Scope

- **§7.1, functions whose body is a `shnex:` node expression** (W3C `instanceCount-example`). This needs the SHACL 1.2 Node Expressions evaluator and gets a separate plan. That case stays in `W3cKnownDeviations` as `SHACL_FUNCTION`.
- **Enforcing parameter constraints** (`sh:datatype` etc.). §7.2 says they "are not automatically enforced", so they aren't.
- **Using `sh:defaultValue` at run time.** §7.2 says it is not used.
- **Remote SPARQL endpoints.** They refuse sandboxed queries, and SHACL already falls back to a private copy, so they need no work.

## Review Focus

1. **A declared function that reuses a built-in IRI** (e.g. `fn:concat`, `xsd:integer`) must leave the built-in in charge (§7.3). Test: Task 2 and Task 3, `a declaration cannot shadow a built-in function`.
2. **A function that calls itself, directly or through another function**, must end with an error, not a stack overflow or a hang. Test: Task 4, `recursive functions end at the depth limit`.
3. **Two validations running at once with different shapes graphs** must each see only their own functions (no global registration). Test: Task 4, `concurrent validations see only their own functions`.
4. **Argument count:** extra arguments are an error. Missing trailing arguments are unbound when their parameter is optional, otherwise an error. Test: Task 4, `argument count follows the parameter declarations`.
5. **A body that yields zero or several rows** makes the call an error, so the variable is unbound. Test: Task 4, `a body with zero or several results is an error`.

---

### Task 1: Provider-neutral function contract

**Files:**
- Modify: `rdf/sparql/contract/src/main/kotlin/com/geoknoesis/kastor/rdf/SparqlMarkers.kt`. `SandboxedSelectQuery` becomes a regular class (no longer a value class) with a second constructor parameter.
- Create: `rdf/sparql/contract/src/main/kotlin/com/geoknoesis/kastor/rdf/SparqlFunctions.kt`
- Modify: `rdf/sparql/contract/api/sparql-contract.api`, regenerated.
- Test: `rdf/sparql/contract/src/test/kotlin/com/geoknoesis/kastor/rdf/SparqlFunctionsTest.kt`. Add JUnit to the module's test dependencies if it has none, matching how `rdf/core` declares them.

**Interfaces (produced):**

```kotlin
class SandboxedSelectQuery(override val sparql: String, val functions: SparqlFunctionLibrary = SparqlFunctionLibrary.EMPTY) : SparqlSelect
// equals/hashCode on sparql + functions (identity of the library)

/** Custom functions visible to one sandboxed query and to every query its function bodies run. */
fun interface SparqlFunctionLibrary {
    fun function(iri: String): SparqlFunction?
    companion object {
        val EMPTY: SparqlFunctionLibrary
        fun of(functions: Collection<SparqlFunction>): SparqlFunctionLibrary // later duplicates replace earlier ones
    }
}

class SparqlFunction(
    val iri: String,
    /** One entry per declared parameter, in argument order: true when the parameter is optional. */
    val optional: List<Boolean>,
    /** Returns the single result node, or null for an error. Never called with a blank node argument. */
    val body: (SparqlFunctionCall) -> RdfTerm?,
)

interface SparqlFunctionCall {
    /** Evaluated arguments, one per declared parameter; null for an unbound optional argument. */
    val arguments: List<RdfTerm?>
    /** Runs [query] on the dataset of the calling query, within its remaining time, one nesting level deeper. */
    fun <T> select(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>, consume: (Sequence<BindingSet>) -> T): T
}

const val MAX_FUNCTION_DEPTH: Int = 16

/**
 * The arguments a provider passes to [SparqlFunction.body]: [evaluated] holds one entry per actual argument
 * (null = evaluation error). Returns null when the call is an error: more arguments than parameters; an error, a
 * missing argument or a blank node for a non-optional parameter. Optional parameters with an error, a blank node
 * or no argument become null.
 */
fun SparqlFunction.bindArguments(evaluated: List<RdfTerm?>): List<RdfTerm?>?
```

- [ ] **Step 1: Write the failing tests** in `SparqlFunctionsTest`, using `SparqlFunction("urn:f", listOf(false, true)) { null }`:
  - `bindArguments maps errors and missing optional arguments to null`:
    - `bindArguments(listOf(Iri("urn:a")))` gives `listOf(Iri("urn:a"), null)`.
    - `bindArguments(listOf(Iri("urn:a"), null))` gives `listOf(Iri("urn:a"), null)`.
  - `bindArguments rejects what the spec makes an error`. Each of these gives null:
    - `listOf(null, Iri("urn:b"))`;
    - `listOf(BlankNode("b"))`;
    - `listOf()`;
    - three arguments.
  - `library lookup`:
    - `SparqlFunctionLibrary.of(listOf(f)).function("urn:f") === f`;
    - `EMPTY.function("urn:f") == null`.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:sparql-contract:test`. Expected: compilation failure.
- [ ] **Step 3: Implement the contract** in `SparqlFunctions.kt`, with KDoc that quotes the §7.3 rules. Change `SandboxedSelectQuery` to the class above. Its existing callers (`SparqlConstraintEvaluator`, the provider tests) compile unchanged.
- [ ] **Step 4: Regenerate and check the ABI, then run the tests; they should pass.** Run `bash /c/tmp/kastor-gradle.sh :rdf:sparql-contract:updateLegacyAbi :rdf:sparql-contract:test :rdf:jena:test --tests '*Sandbox*' :rdf:rdf4j:test --tests '*Sandbox*' :rdf:sparql:test --tests '*Sandbox*'`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `git commit -m "feat(sparql): provider-neutral custom function contract for sandboxed queries"`

### Task 2: Jena evaluates library functions

**Files:**
- Modify: `rdf/providers/jena/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/JenaQuerySandbox.kt`. `restrict(builder)` becomes `restrict(builder, functions: SparqlFunctionLibrary, depth: Int, deadline: Long)`, and `NoJavaFunctions` consults the library.
- Create: `rdf/providers/jena/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/JenaLibraryFunction.kt`
- Modify: `rdf/providers/jena/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/JenaRepository.kt`, where `execution(query, parsed)` passes `query.functions`.
- Test: `rdf/providers/jena/src/test/kotlin/com/geoknoesis/kastor/rdf/jena/JenaSparqlFunctionTest.kt`

**Interfaces:**
- Consumes: Task 1's `SparqlFunction`, `SparqlFunctionLibrary`, `SparqlFunctionCall`, `bindArguments` and `MAX_FUNCTION_DEPTH`.
- Produces: `internal class JenaLibraryFunction(function: SparqlFunction, depth: Int, deadline: Long) : org.apache.jena.sparql.function.Function`.

- [ ] **Step 1: Write the failing tests** in `JenaSparqlFunctionTest`. Use a memory repository with the data `<urn:a> <urn:l> "x"@en , "y"@en , "z"@de` and a test library:
  - `spaced` (2 mandatory parameters) returns `concat(arg0, " ", arg1)`, computed in Kotlin.
  - `count` (1 mandatory, 1 optional) runs `call.select(SandboxedSelectQuery("SELECT (COUNT(?l) AS ?n) WHERE { ?s <urn:l> ?l FILTER langMatches(lang(?l), COALESCE(?lang, 'en')) }"), mapOf("s" to arguments[0]) + (arguments[1]?.let { mapOf("lang" to it) } ?: emptyMap()))` and returns `?n`.

  The tests:
  - `library functions are called with evaluated arguments`:
    - `SELECT ?v WHERE { BIND (<urn:spaced>('John', 'Doe') AS ?v) }` gives `"John Doe"`.
    - `<urn:count>(<urn:a>)` gives `2`.
    - `<urn:count>(<urn:a>, 'de')` gives `1`.
  - `errors follow SPARQL semantics`:
    - `<urn:count>(?unbound)` leaves `?v` unbound;
    - so does `<urn:spaced>('a')` (missing mandatory argument);
    - so does `<urn:spaced>('a', 'b', 'c')`.
  - `a declaration cannot shadow a built-in function` (Review Focus 1): a library function for `http://www.w3.org/2005/xpath-functions#concat` that returns `"hijacked"` is ignored, and `fn:concat('a','b')` gives `"ab"`.
  - `nested queries stay sandboxed`: a body whose nested query contains `SERVICE <http://127.0.0.1:1/s> {}` makes the call an error (`?v` unbound). No request is made, and the outer query still returns its row.
  - `recursion ends at the depth limit`: a function whose body calls itself through `call.select("SELECT (<urn:self>() AS ?x) {}")` ends with `?v` unbound, without a `StackOverflowError`.
  - `functions are per query`: the same SELECT sent without the library leaves `<urn:spaced>(…)` unbound.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:jena:test --tests '*JenaSparqlFunctionTest*'`. Expected: FAIL, with `?v` unbound in the positive cases.
- [ ] **Step 3: Implement `JenaLibraryFunction` and the registry changes.**
  - **Lookup in `NoJavaFunctions.getFunctionFactory(uri)`:** return the base (built-in) factory if there is one. Otherwise, a library function gives `FunctionFactory { JenaLibraryFunction(it, depth, deadline) }`, and anything else gives null.
  - **Argument evaluation in `JenaLibraryFunction.exec(binding, args, uri, env)`:**
    - Evaluate each argument with `expr.eval(binding, env)`, catching `ExprEvalException` as null.
    - Convert each value through `JenaTerms.fromNode`.
    - Apply `bindArguments`.
    - On null, throw `ExprEvalException`.
  - **Nested queries, in `call.select`:**
    - Build `QueryExecution.dataset(DatasetFactory.wrap(env.dataset))`.
    - Restrict it with `depth + 1`; at depth ≥ `MAX_FUNCTION_DEPTH`, throw `ExprEvalException` instead.
    - Apply the bindings by `substitution`.
    - Set the timeout to the remaining part of `deadline`, and stream the rows.
  - **Return value:** the body's non-null result becomes a `NodeValue`. A null result or a thrown `RuntimeException` throws `ExprEvalException`.
- [ ] **Step 4: Run the tests; they should pass.** Run the Step 2 command, then `:rdf:jena:test :rdf:jena:checkLegacyAbi`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(jena): evaluate sandboxed custom SPARQL functions per query"`

### Task 3: RDF4J evaluates library functions

**Files:**
- Create: `rdf/providers/rdf4j/src/main/kotlin/com/geoknoesis/kastor/rdf/rdf4j/Rdf4jFunctionEvaluation.kt`. It holds `KastorEvaluationStrategyFactory`, `KastorEvaluationStrategy` and the per-prepare library hand-off.
- Modify: `rdf/providers/rdf4j/src/main/kotlin/com/geoknoesis/kastor/rdf/rdf4j/Rdf4jRepository.kt` and the store construction in `Rdf4jProvider.kt`. Install `KastorEvaluationStrategyFactory` on every `MemoryStore` / `NativeStore` that Kastor creates. Make the sandboxed `withSelectRows` paths make `query.functions` available while the query is prepared.
- Test: `rdf/providers/rdf4j/src/test/kotlin/com/geoknoesis/kastor/rdf/rdf4j/Rdf4jSparqlFunctionTest.kt`

**Interfaces:**
- Consumes: Task 1's contract.
- Produces:
  - `internal class KastorEvaluationStrategy(tripleSource: TripleSource, dataset: Dataset?, resolver: FederatedServiceResolver?, …) : DefaultEvaluationStrategy`. It overrides `prepare(node: FunctionCall, context: QueryEvaluationContext): QueryValueEvaluationStep`.
  - `internal object Rdf4jFunctionScope { fun <T> with(functions: SparqlFunctionLibrary, depth: Int, deadline: Long, block: () -> T): T; val current: Scope? }`. This is a thread-local, read only during `prepare`.

- [ ] **Step 1: Write the failing tests** in `Rdf4jSparqlFunctionTest`. They are the same six tests as Task 2 Step 1, with the same data, library and expected values, run against an RDF4J memory repository. Add one more:
  - `no global registration`: after the tests, `org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry.getInstance().has("urn:spaced")` is false.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:rdf4j:test --tests '*Rdf4jSparqlFunctionTest*'`. Expected: FAIL, because RDF4J rejects the unknown function.
- [ ] **Step 3: Implement.**
  - **`prepare(FunctionCall)`:**
    - If `FunctionRegistry.getInstance()` has the IRI, use `super.prepare`. That keeps built-ins in charge, and RDF4J never loads classes by name.
    - Otherwise, if `Rdf4jFunctionScope.current` has a library function for the IRI, prepare the argument steps with `precompile(arg, context)`.
    - Otherwise, use `super.prepare`, which raises RDF4J's unknown-function error.
  - **The returned `QueryValueEvaluationStep`:**
    - Evaluate each argument step, catching `ValueExprEvaluationException` as null.
    - Convert through `Rdf4jTerms`, then apply `bindArguments`. On null, throw `ValueExprEvaluationException`.
    - Otherwise call the body.
  - **Nested queries, in `call.select`:**
    - Apply bindings with `SparqlInitialBindings.apply`.
    - Run the provider's sandbox check on the text.
    - Parse it with `SPARQLParser`.
    - Evaluate it with a new `KastorEvaluationStrategy` over the same `tripleSource` and `dataset`, inside `Rdf4jFunctionScope.with(…, depth + 1, …)`. At depth ≥ `MAX_FUNCTION_DEPTH`, the call is an error.
    - Bound the nested query by `deadline` and stream it.
  - **`withSelectRows` for a `SandboxedSelectQuery`:** wrap the `prepare`/`evaluate` part in `Rdf4jFunctionScope.with(query.functions, 0, now + timeout)`.
- [ ] **Step 4: Run the tests; they should pass.** Run the Step 2 command, then `:rdf:rdf4j:test :rdf:rdf4j:checkLegacyAbi`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(rdf4j): evaluate sandboxed custom SPARQL functions per query"`

### Task 4: Compile `sh:ListParameterExpressionFunction` declarations and send them with every SHACL query

**Files:**
- Create: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/native/SparqlFunctionDeclarations.kt`
- Modify: `native/ShaclSparqlTerms.kt`, adding `ListParameterExpressionFunction` and `bodyExpression`.
- Modify: `native/ShapesCompiler.kt`:
  - add `CompiledShapeGraph.functions: SparqlFunctionLibrary`;
  - change `detectUnsupportedFeatures` so `SHACL_FUNCTION` is reported only for functions whose body does not compile to a `NodeExpression.Select`.
- Modify: `native/SparqlConstraintEvaluator.kt`, so `Session` takes `functions: SparqlFunctionLibrary` and sends `SandboxedSelectQuery(query, functions)`.
- Modify: `providers/NativeValidationContext.kt`, passing `compiled.functions`.
- Modify: `src/test/kotlin/…/shacl/conformance/Shacl12NativeConformanceTest.kt`, removing the `spacedConcat-example.ttl` and `langLabelCount-example.ttl` deviation entries.
- Test: `src/test/kotlin/…/shacl/SparqlFunctionTest.kt`

**Interfaces:**
- Consumes:
  - `NodeExpressions.compile`;
  - `SparqlQueryTemplate.compileRestricted` / `bind`;
  - `SparqlQueryTemplate.localName`;
  - Task 1's contract.
- Produces: `internal object SparqlFunctionDeclarations { fun compile(index: ShapeGraphIndex): SparqlFunctionLibrary }`.

- [ ] **Step 1: Write the failing tests** in `SparqlFunctionTest`, parameterized over `jena` and `rdf4j`, with the `validate(provider, data, shapes)` helper:
  - `spacedConcat example`: the shapes of `spacedConcat-example.ttl` give one Warning result with `sh:value "John Doe"`.
  - `langLabelCount example`: the shapes of `langLabelCount-example.ttl` give `sh:value "2 2 1 false"`.
  - `argument count follows the parameter declarations` (Review Focus 4): with `spacedConcat`, `ex:spacedConcat('a')` and `ex:spacedConcat('a','b','c')` both leave the variable unbound.
  - `a body with zero or several results is an error` (Review Focus 5): a body `SELECT ?x WHERE { VALUES ?x { 1 2 } }` and a body `SELECT ?x WHERE { FILTER (false) }` both leave the variable unbound.
  - `recursive functions end at the depth limit` (Review Focus 2): `ex:loop` with body `[ sh:sparqlExpr "ex:loop()" ]` leaves the variable unbound, and validation completes.
  - `concurrent validations see only their own functions` (Review Focus 3): two shapes graphs declare `ex:f` returning `"one"` and `"two"`. Validate them on two threads, 50 times each, and check that every result matches its own shapes graph.
  - `ill-formed declarations`: each of these throws `ShapeCompileException`:
    - a function that is not an IRI;
    - two `sh:bodyExpression` values;
    - a parameter whose `sh:path` local name is not `arg<n>`;
    - parameter indexes with gaps (`arg0`, `arg2`);
    - an optional parameter before a mandatory one;
    - a body `SELECT ?x { BIND (1 AS ?arg0) }`, which re-binds a scope variable. This one throws `SparqlPreBindingRestrictionException`.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*SparqlFunctionTest*'`. Expected: FAIL, with `UnsupportedShaclFeatureException` (`SHACL_FUNCTION`).
- [ ] **Step 3: Implement `SparqlFunctionDeclarations.compile`.**
  - **Which functions:** IRIs with `index.isInstanceOf(f, ListParameterExpressionFunction)`, each with exactly one `sh:bodyExpression`.
  - **Parameters:** sort them by the `n` of the `arg<n>` local name of their `sh:path`. The indexes must be contiguous from 0, and no mandatory parameter may follow an optional one. A parameter is optional when it has `sh:optional true`.
  - **Body:** compile it with `NodeExpressions.compile`, with pre-bound names `{this, arg0 … arg<n-1>}`. This needs a `preBound` parameter on `NodeExpressions.compile`, defaulting to `setOf("this")`.
  - **A body that is not a `Select`:** the function is skipped here and reported by detection as `SHACL_FUNCTION`.
  - **What the body does when called:**
    - bind `this` to the function IRI and `arg<i>` to each non-null argument;
    - run `call.select(SandboxedSelectQuery(boundQuery, library), providerBindings)`;
    - read the projected variable;
    - return the node when exactly one row has it bound, otherwise null.
  - **Function chaining:** the library passes itself on, so functions can call each other.
  - **Budget:** tick `index.tick` in the loops.
- [ ] **Step 4: Run the tests; they should pass.** Run the Step 2 command, then `:rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`. Expected: PASS. Of the function cases, `W3cKnownDeviations` now lists only `sparql/functions/instanceCount-example.ttl`.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): SHACL 1.2 custom SPARQL functions with SPARQL bodies"`

### Task 5: Documentation

**Files:**
- Modify:
  - `docs/kastor/features/shacl-validation.md`;
  - `docs/kastor/guides/how-to-validate-shacl.md`;
  - `docs/kastor/getting-started/kastor-in-5-minutes.md`;
  - `rdf/shacl/validation/README.md`;
  - the `UnsupportedShaclFeature.SHACL_FUNCTION` KDoc in `ShaclValidationExceptions.kt`.

- [ ] **Step 1: Update the docs.**
  - **Supported:** functions whose body is `sh:select` / `sh:sparqlExpr`, callable from any SHACL-SPARQL query, per query and never registered globally. Built-ins cannot be redefined.
  - **Rules:** the nesting limit (16), and the error semantics for argument count, optional parameters and blank nodes.
  - **Not supported:** `shnex:` bodies (`SHACL_FUNCTION`).
  - **Deviations:** the W3C deviation count drops from 4 to 2.
- [ ] **Step 2: Verify.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit.** `git commit -m "docs(shacl): document custom SPARQL functions"`
