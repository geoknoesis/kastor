# SHACL 1.2 Node Expressions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement SHACL 1.2 Node Expressions in Kastor's native SHACL engine:
- the `shnex:` library;
- `sparql:<name>` function expressions;
- custom named- and list-parameter functions;
- the `sh:expression` and `sh:nodeByExpression` constraint components.

As a result, the 143 approved `sht:EvalNodeExpr` W3C cases run and pass, and the last two `W3cKnownDeviations` (`expression-001`, `instanceCount-example`) are removed.

**Architecture:**
- **Model and parser.** `NodeExpressions` (in `native/`) becomes a parser that compiles any well-formed node expression into a sealed `NodeExpression` tree. Ill-formed expressions are compile errors.
- **Evaluator.** A new evaluator in `providers/` computes `evalExpr(expr, focusGraph, focusNode, scope)`. It returns either a list of output nodes or an evaluation failure. Its inputs come from `NativeValidationContext`:
  - the focus graph is the data index;
  - conformance checks use the engine's own `conformance(...)`;
  - SPARQL work uses the existing sandboxed `ctx.select`.
- **SPARQL functions.** A `sparql:<name>` call is rendered as a one-expression `SELECT` with its arguments as initial bindings. The provider evaluates it, so SPARQL semantics, including errors and unbound values, come from Jena/RDF4J and are not reimplemented.
- **Integration.** The expressions plug into the positions the engine already has: `sh:values`, `sh:defaultValue`, `sh:targetNode`, and the two new constraint components.

**Tech Stack:** Kotlin/JVM, JUnit 5 parameterized over `jena`/`rdf4j`. Run Gradle only via `bash /c/tmp/kastor-gradle.sh`.

**Spec:** https://www.w3.org/TR/shacl12-node-expr/ (SHACL 1.2 Node Expressions). Executors: save a text copy into the SDD workspace before Task 1, with `curl -sL … > node-expr.html`, then strip the tags. Briefs cite sections by number. Appendix A, "Summary of Syntax Rules", is the authority on well-formedness. W3C cases live in `rdf/shacl/validation/test-data/w3c-shacl12/tests/node-expr/` (`shnex/`, `shnex-sparql/`, `constraints/`).

## Global Constraints

- **Output.** Output nodes are a **list**: order and duplicates matter unless the spec says otherwise. Intersection has undefined order. Count, sum and exists produce one node.
- **Equality.** `shnex:distinct`, `shnex:intersection` and `shnex:remove` compare nodes by **term equality**. Spec 4.2.1, verbatim: "`"01"^^xsd:integer` is distinct from `"1"^^xsd:integer`". Do not use `shaclRdfTermEquals` or the fingerprint here, because those compare by value.
- **Failure propagation (§3.3, verbatim).** "if any such nested expressions produce a failure then the surrounding expression also produces the same failure." SPARQL function expressions are the exception: a failing argument is a SPARQL error.
- **List parameter functions (§3.2.2, verbatim):**
  - "If an argument produces more than one output node, then the evaluation of that argument produces an evaluation failure"
  - "If no argument produces an evaluation failure and an argument produces no output node, then the function is not applied and the output nodes of the expression are the empty list, unless the function is defined to handle unbound values (such as sparql:bound, sparql:coalesce and sparql:if)"
  - "A failing argument thus takes precedence over an argument without output nodes, whatever their order."
- **SPARQL functions (§5).** The unbound-handling set is exactly `sparql:bound`, `sparql:coalesce`, `sparql:if`, `sparql:logical-and` and `sparql:logical-or`. A SPARQL error from the function is an evaluation failure.
- **Laziness.** `shnex:if` evaluates `shnex:then` / `shnex:else` only when they are selected. Spec 4.1.6: "Implementations MUST apply lazy evaluation techniques".
- **Shapes graph (§3.4).** Shape-based functions use the shapes graph of the surrounding validation. In the W3C `EvalNodeExpr` harness the focus graph also serves as the shapes graph.
- **Validation errors.** During validation, an evaluation failure is a validation failure (`ShaclValidationException`), as SPARQL failures already are. Ill-formed expressions are `ShapeCompileException`.
- **Bounds.** Hostile input must stay bounded:
  - Evaluation ticks `ctx.budget` per output node.
  - Every intermediate list is capped at `ctx.data.maxPathValueNodes`, with the existing message style, producing a failure.
  - Evaluation nesting, including custom function bodies, is capped at `MAX_NODE_EXPRESSION_DEPTH = 128`, an internal const. Beyond it, the result is an evaluation failure.
- **Sandboxing.** SPARQL work goes only through `ctx.select` / `SandboxedSelectQuery`, so the provider sandbox applies.
- **Constants.** Constants are unchanged: IRIs, literals and triple terms evaluate to themselves (§3.1).
- **Out of scope.**
  - Non-constant `sh:deactivated` stays reported as `NODE_EXPRESSION`.
  - SPARQL node expressions at `sh:targetWhere` stay reported (ruling R1 of the SPARQL plan).
  - §8 "Dynamic SHACL" examples need nothing beyond the components above.

## Review Focus

1. **Error versus empty precedence in list-parameter and SPARQL functions.** A failing argument wins over an empty one, whatever the order. Test (Task 2 / Task 4): `failing argument beats empty argument in either order`.
2. **Term equality, not value equality**, in distinct, intersection and remove. Test (Task 2): `distinct keeps 01 and 1 apart`.
3. **A shape referenced only from a node expression**, e.g. `shnex:filterShape ex:S` where `ex:S` is not otherwise reachable, must still be compiled and used. Test (Task 3): `shape reachable only from an expression is compiled`.
4. **Runaway expressions.** Recursive custom functions, deeply nested expressions, and `shnex:nodesMatching` over a large graph must stop with a failure or the budget, not overflow the stack. Test (Task 5): `recursive custom function fails at the depth limit`. Test (Task 2): `list cap produces a failure`.
5. **A node expression failing inside validation** gives a `ShaclValidationException`, not silent conformance. Test (Task 6): `failing sh:values expression fails validation`.

---

### Task 1: Parse every node expression into a typed tree

**Files:**
- Modify: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/native/NodeExpressions.kt`. Extend the sealed `NodeExpression`; `compile(term, index, preBound)` stays the entry point.
- Create: `…/shacl/native/NodeExpressionParser.kt` (the per-function parsing and well-formedness).
- Modify: `…/shacl/native/ShaclSparqlTerms.kt` to add the `shnex:` and `sparql:` namespace constants.
- Test: `…/shacl/native/NodeExpressionParserTest.kt`

**Interfaces:**
- Produces sealed subclasses of `NodeExpression`, all `data class`es over child `NodeExpression`s:
  - `Constant`
  - `Empty`
  - `Var(name)`
  - `ListExpr(members)`
  - `PathValues(path: ShaclPath, focusNode: NodeExpression?)`
  - `Exists(e)`
  - `If(cond, then, else)`
  - `Distinct(e)`
  - `Intersection(members)`
  - `Concat(members)`
  - `Remove(remove, nodes)`
  - `FilterShape(shape: RdfResource, nodes)`
  - `Limit(n: Int, nodes)`
  - `Offset(n: Int, nodes)`
  - `OrderBy(key, nodes, desc: Boolean)`
  - `FlatMap(e, nodes?)`
  - `FindFirst(shape, nodes?)`
  - `MatchAll(shape, nodes?)`
  - `Count(e)`, `Min(e)`, `Max(e)`, `Sum(e)`
  - `InstancesOf(e)`
  - `NodesMatching(shape)`
  - `ConformsToShape(node, shape)`
  - `SparqlCall(name: String, args: List<NodeExpression>)`
  - `CustomNamed(function: Iri, args: Map<Iri, NodeExpression>)`
  - `CustomList(function: Iri, args: List<NodeExpression>)`
  - `Arg(index: Int)`
  - `Select` (unchanged)
- `NodeExpressions.compile` returns `null` only for blank nodes that are not node expressions at all, such as data nodes.

- [ ] **Step 1: Write the failing tests** in `NodeExpressionParserTest`:
  - `every W3C nodeExpr parses`: for each `sht:nodeExpr` value in `node-expr/shnex/*.ttl` and `node-expr/shnex-sparql/*.ttl` (143 cases), `compile` returns non-null without throwing. Skip the suite when the test data is absent, as other W3C-dependent tests do.
  - `list parameter syntax rules (§3.2.2)`:
    - `[ sparql:abs -42 ]` equals `[ sparql:abs ( -42 ) ]`;
    - `[ sparql:now () ]` has no arguments;
    - `[ ex:f ( ( 1 2 ) ) ]` has one argument, a `ListExpr`.
  - `ill-formed expressions are compile errors`: each of these throws `ShapeCompileException`:
    - two `shnex:if` values;
    - `shnex:limit -1`;
    - `shnex:limit "x"`;
    - `shnex:pathValues` with a malformed path;
    - a blank node with two key parameters of different functions.

    Check each rule against Appendix A.
  - `custom functions are recognised`:
    - a blank node `[ ex:f ( 1 2 ) ]`, where `ex:f a sh:ListParameterExpressionFunction`, gives `CustomList`;
    - `[ ex:g 1 ]`, where `ex:g` is declared as a named-parameter function (§6.1), gives `CustomNamed`.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*NodeExpressionParserTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement the parser.**
  - Recognise named-parameter functions by key parameter. Recognise list-parameter functions as a single triple whose object is a list, or a single node expression for one argument.
  - Recognise `sparql:` names from the §5 table, and custom functions from their declarations (§6).
  - Use the Appendix A rules for well-formedness. Tick `index.tick` per node visited, and use the existing `ShaclPathParser` for paths.
  - Do not change how existing callers behave: `Select` and `Constant` must still compile identically, and the detection code keeps working because `compile` still returns null for non-expressions.
- [ ] **Step 4: Run the tests; they should pass.** Run the Step 2 command and `:rdf:shacl-validation:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): parse SHACL 1.2 node expressions into a typed tree"`

### Task 2: Evaluator for the non-shape, non-SPARQL library, plus the W3C `EvalNodeExpr` harness

**Files:**
- Create: `…/shacl/providers/NativeNodeExpressionEvaluator.kt`. It moves `evalNodeExpression` out of `NativeNodeExpressions.kt`, whose callers keep the same signature.
- Modify: `…/shacl/providers/NativeShaclValidator.kt` to add an internal entry point.
- Create: `src/test/kotlin/…/shacl/conformance/Shacl12NodeExprConformanceTest.kt`, which is tagged `w3c` and also runs in the default `test` task like the existing W3C class.
- Test: `src/test/kotlin/…/shacl/NodeExpressionEvaluationLibraryTest.kt`

**Interfaces:**

```kotlin
internal sealed interface NodeExprOutcome {
  data class Nodes(val nodes: List<RdfTerm>) : NodeExprOutcome
  data class Failure(val reason: String) : NodeExprOutcome
}
internal class NodeExprScope(val focus: RdfTerm?, val variables: Map<String, RdfTerm>, val args: List<RdfTerm?>, val depth: Int)
internal fun NativeShaclValidator.evaluate(expr: NodeExpression, ctx: NativeValidationContext, scope: NodeExprScope): NodeExprOutcome
// existing callers:
internal fun NativeShaclValidator.evalNodeExpression(expr: NodeExpression, ctx: NativeValidationContext, focus: RdfTerm?): List<RdfTerm> // throws ShaclValidationException on Failure
// harness entry point:
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, exprNode: RdfTerm, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome
```

`evaluateNodeExpression` uses `graph` as both the focus graph and the shapes graph.

- [ ] **Step 1: Write the failing tests.**
  - **Harness.** `Shacl12NodeExprConformanceTest` collects every `sht:EvalNodeExpr` entry under `node-expr/` through the existing manifest parser's `mf:include` handling, extended for the new type. Each case is evaluated with:
    - `sht:focusNode` as the focus;
    - each `sht:scope-<name> value` as variable `name`;
    - the test file as the graph.

    The output must equal the `mf:result` list. When `sht:ignoreOrder true` is set, the comparison is a multiset comparison. Comparison uses term equality.
  - **Pending list.** Cases listed in a new `W3cNodeExprPending` map (file → reason) must fail with `NodeExprOutcome.Failure` whose reason starts with `"unsupported:"`; they don't pass silently. Initially the map lists every case that needs Tasks 3–5: shape functions, `sparql:`, and custom/arg.
  - **`NodeExpressionEvaluationLibraryTest`:**
    - `distinct keeps 01 and 1 apart` (Review Focus 2): `[ shnex:distinct ( "01"^^xsd:integer "1"^^xsd:integer "01"^^xsd:integer ) ]` gives two nodes.
    - `if is lazy`: `[ shnex:if true ; shnex:then 1 ; shnex:else [ <failing expression> ] ]` gives `( 1 )`.
    - `list cap produces a failure` (Review Focus 4): with `maxPathValueNodes = 2`, `[ shnex:concat ( ( 1 2 ) ( 3 ) ) ]` gives a `Failure`.
    - `var focusNode and scope`: §4.1.2's three rules.
    - `min max sum follow SPARQL`: `shnex:min ( 3 1 2 )` gives `1`; `shnex:sum ( 1 2.5 )` gives `3.5` as `xsd:decimal`; `shnex:sum ( "a" )` gives a `Failure`.
    - `orderBy desc and keys`: from the spec examples in §4.2.8.
- [ ] **Step 2: Run the tests; they should fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*NodeExpressionEvaluationLibraryTest*' --tests '*Shacl12NodeExprConformanceTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `evaluate`** for these functions: Constant, Empty, Var, ListExpr, PathValues, Exists, If, Distinct, Intersection, Concat, Remove, Limit, Offset, OrderBy, FlatMap, Count, Min, Max, Sum, InstancesOf, Select (existing). Tasks 3–5 functions return `Failure("unsupported: <function>")`.
  - **Path values** use `PathEvaluator` over `ctx.data`.
  - **InstancesOf** uses `ctx.data.instancesMatchingTargetClass`, with a failure for any non-IRI type (§4.5.1).
  - **Ordering for OrderBy / Min / Max** is SPARQL's ORDER BY order: unbound, then blank nodes, then IRIs, then literals. Comparable literals use `tryCompareLiterals`; everything else falls back to a deterministic lexical order.
  - **Sum** is computed by the provider: `SELECT (SUM(?v) AS ?r) WHERE { VALUES ?v { … } }` through `ctx.select`. This is an internal query, not subject to shapes-graph restrictions. Non-literals give a failure.
  - **Bounds.** Tick the budget, apply the list cap, and enforce the depth cap.
- [ ] **Step 4: Run the tests; they should pass.** Run the Step 2 command, `:rdf:shacl-validation:test` and `:rdf:shacl-validation:w3cConformanceTest`. Expected: PASS. Every non-pending `EvalNodeExpr` case passes.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): evaluate the shnex node expression library; run W3C EvalNodeExpr cases"`

### Task 3: Shape-based functions

**Files:**
- Modify: `NativeNodeExpressionEvaluator.kt`.
- Modify: `native/ShapesCompiler.kt` so `shapeReachableNodes` and the compile set include shapes referenced from node expressions in every supported position.
- Modify: `W3cNodeExprPending` (remove the cases this task covers).
- Test: extend `NodeExpressionEvaluationLibraryTest`.

**Interfaces:**
- Consumes: `NativeShaclValidator.conformance(value, ref, ctx, DepthState(0, Mode.CONFORMS))`.

- [ ] **Step 1: Write the failing tests.**
  - `filterShape findFirst matchAll nodesMatching conformsToShape`: the spec examples from §4.2.5, §4.3.2, §4.3.3, §4.5.2 and §4.5.3.
  - `shape reachable only from an expression is compiled` (Review Focus 3): a `sh:values [ shnex:filterShape ex:S ; shnex:nodes … ]` where `ex:S` is referenced nowhere else filters correctly.
  - `undecided conformance is a failure`: a recursive non-monotone shape gives `Conformance.UNDEFINED`, and that produces `Failure`.
- [ ] **Step 2: Run the tests; they should fail.** Expected: `Failure("unsupported: …")`.
- [ ] **Step 3: Implement the five functions with these rules.**
  - `CONFORMS` keeps the node, `FAILS` drops it, and `UNDEFINED` is an evaluation failure (§3.4: "If the validation performed by such a function produces a failure, the node expression produces an evaluation failure").
  - `nodesMatching` iterates `ctx.data.allNodes()` with budget ticks.
  - `conformsToShape` follows §4.5.3 for empty arguments.
- [ ] **Step 4: Run the tests; they should pass.** Run the focused tests, the module tests and the W3C tests. Expected: PASS, with these cases removed from pending.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): shape-based node expressions"`

### Task 4: `sparql:<name>` function expressions via the provider

**Files:**
- Create: `…/shacl/native/SparqlFunctionExpressions.kt`. It holds the name table: `sparql:<name>` maps to a SPARQL rendering, either a function `NAME(…)` or an operator such as `plus` → `(?a0 + ?a1)`, `logical-not` → `(!?a0)` or `unary-minus` → `(-?a0)`. It also holds the unbound-handling set and the arity of each name.
- Modify: `NativeNodeExpressionEvaluator.kt` and `W3cNodeExprPending`.
- Test: `src/test/kotlin/…/shacl/SparqlFunctionExpressionTest.kt` (parameterized over `jena`/`rdf4j`).

**Interfaces:**
- Produces: `internal object SparqlFunctionExpressions { fun render(name: String, arity: Int): String?; val handlesUnbound: Set<String> }`. `render` returns, for example, `"(?a0 + ?a1)"`, or null for an unknown name, which is ill-formed at compile time in Task 1. Back-fill that check there.

- [ ] **Step 1: Write the failing tests.**
  - `every sparql function in the spec table renders`: each name in §5's table renders, and the W3C `shnex-sparql` cases cover the semantics.
  - `failing argument beats empty argument in either order` (Review Focus 1): `[ sparql:concat ( [ shnex:limit 0 ; shnex:nodes ( 1 ) ] [ sparql:abs ( ( 1 2 ) ) ] ) ]` gives a `Failure`, with the arguments in both orders.
  - `unbound handling`, from §5's own examples:
    - `[ sparql:coalesce ( ( 1 2 ) "ok" ) ]` gives `( "ok" )`;
    - `[ sparql:abs ( ( -1 -2 ) ) ]` gives a `Failure`;
    - `[ sparql:bound ( [ shnex:var "x" ] ) ]` gives `( false )`.
  - `empty argument means no output`: `[ sparql:abs ( [] ) ]` gives `()`.
- [ ] **Step 2: Run the tests; they should fail.**
- [ ] **Step 3: Implement evaluation.**
  1. Evaluate each argument.
  2. Apply §3.2.2 and §5: failure precedence, the more-than-one-output rule, and the empty-argument rule except for `handlesUnbound`.
  3. Bind the remaining values as `a0…an` initial bindings, leaving unbound ones out, and run `SELECT (<render> AS ?r) WHERE {}` through `ctx.select`. Use `SandboxedSelectQuery` with an empty library.
  4. One row with `?r` bound gives `( r )`. A row without `?r`, or a query error, is a SPARQL error and therefore a `Failure`.

  If one provider lacks a SPARQL 1.2 function, such as `sparql:hasLangdir` or `sameValue` on RDF4J, the outcome on that provider is `Failure`. Record that in the report. The harness runs on the engine's default provider, so name it in the report.
- [ ] **Step 4: Run the tests; they should pass.** Run the focused tests, the module tests and the W3C tests. Expected: PASS, with all `shnex-sparql` cases removed from pending.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): sparql: function node expressions evaluated by the provider"`

### Task 5: Custom node expression functions, `shnex:arg`, and `shnex:` bodies in SPARQL-callable functions

**Files:**
- Modify: `NativeNodeExpressionEvaluator.kt`, `native/SparqlFunctionDeclarations.kt` and `native/ShapesCompiler.kt`. Narrow `SHACL_FUNCTION` detection to bodies that do not compile at all.
- Modify: `src/test/kotlin/…/shacl/conformance/Shacl12NativeConformanceTest.kt` (remove the `sparql/functions/instanceCount-example.ttl` deviation) and `W3cNodeExprPending` (which should now be empty, so delete it).
- Test: extend `NodeExpressionEvaluationLibraryTest` and `SparqlFunctionTest`.

**Interfaces:**
- Consumes: Task 1's `CustomNamed` / `CustomList` / `Arg`.
- Consumes: from the SPARQL functions plan, `SparqlFunctionDeclarations` and the `SparqlFunctionLibrary` contract in `:rdf:core`.

- [ ] **Step 1: Write the failing tests.**
  - `custom named and list parameter functions`: the §6.1 and §6.2 spec examples, including §6.2's rule that more than one output node is a failure.
  - `arg expressions`: §6.3.
  - `recursive custom function fails at the depth limit` (Review Focus 4): `ex:loop` whose body calls `ex:loop` gives a `Failure`, with no `StackOverflowError`.
  - `instanceCount example`: the W3C `sparql/functions/instanceCount-example.ttl` (a `shnex:` body called from SPARQL) gives `sh:value 2` on both providers.
- [ ] **Step 2: Run the tests; they should fail.**
- [ ] **Step 3: Implement.**
  - **Custom functions.** Evaluate custom functions per §6, with the scope built from the declared parameters and `depth + 1`.
  - **SPARQL-callable functions with `shnex:` bodies.** These need the validation run's context, and the compiled library must stay immutable and shareable across runs (Task 4 of the functions plan). Do it this way:
    - `CompiledShapeGraph.functions` becomes `internal class SparqlFunctionDeclarationSet`, holding the compiled declarations, with `fun library(run: (NodeExpression, NodeExprScope) -> NodeExprOutcome): SparqlFunctionLibrary`.
    - `NativeValidationContext` builds that library once per run, lazily, passing `{ e, s -> validator.evaluate(e, this, s) }`. The `Session` sends the per-run library.
    - SPARQL-bodied functions behave exactly as before.
    - A function whose body compiles to a non-`Select` `NodeExpression` becomes a `SparqlFunction` whose body calls `run(body, NodeExprScope(focus = function IRI, variables = emptyMap(), args = call.arguments, depth = 0))`. Exactly one output node gives the result; anything else, including a `Failure`, gives null (an error).
    - R6 (no in-place queries when functions are declared) keeps applying, because the library is still non-empty.
- [ ] **Step 4: Run the tests; they should pass.** Run the module tests and `w3cConformanceTest`. Expected: PASS. `instanceCount` passes, and `W3cNodeExprPending` is gone.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): custom node expression functions and shnex bodies for SPARQL functions"`

### Task 6: Validation integration: `sh:expression`, computed `sh:nodeByExpression`, and node expressions everywhere the engine accepts them

**Files:**
- Modify:
  - `native/ShapesCompiler.kt`: new `PropertyConstraint.Expression(expr)` and `PropertyConstraint.NodeByExpressionComputed(expr)`; `NodeExpressions.compile` used for `sh:values`, `sh:defaultValue` and `sh:targetNode`, now accepting the full library; narrowed `NODE_EXPRESSION` detection (non-constant `sh:deactivated` only).
  - `providers/NativePropertyConstraints.kt`: dispatch for the new constraints.
  - `ShaclConstraintComponentIris.kt`: `sh:ExpressionConstraintComponent` and `sh:NodeByExpressionConstraintComponent`, exact IRIs per spec §7.
  - `Shacl12NativeConformanceTest.kt`: remove the `node-expr/constraints/expression-001.ttl` deviation, which leaves `W3cKnownDeviations` with no deviations.
- Test: `src/test/kotlin/…/shacl/NodeExpressionConstraintTest.kt`

- [ ] **Step 1: Write the failing tests.**
  - `sh:expression`: spec §7.1's IBAN example, plus `expression-001` from the W3C constraints folder. Each value node `v` where `evalExpr($expr, data graph, v, {})` is not exactly `( true )` gives one result with `sh:value v` and `sourceConstraintComponent sh:ExpressionConstraintComponent`.
  - `sh:nodeByExpression computed`: spec §7.2's example. Each shape produced by the expression is a conformance check for each value node. A failing check fails validation, as §7.2 says: "A failure MUST be produced if the conformance check of v against s produces a failure".
  - `failing sh:values expression fails validation` (Review Focus 5): `sh:values [ sparql:abs ( ( 1 2 ) ) ]` raises `ShaclValidationException`.
  - `shnex at sh:values and sh:targetNode`: `sh:values [ shnex:count [ shnex:pathValues ex:p ] ]` gives the count; `sh:targetNode [ shnex:instancesOf ex:C ]` targets the instances.
  - `non-constant sh:deactivated stays unsupported`: an `UnsupportedShaclFeatureException` of category `NODE_EXPRESSION`.
- [ ] **Step 2: Run the tests; they should fail.**
- [ ] **Step 3: Implement.** Evaluate through `evalNodeExpression` (a failure becomes `ShaclValidationException`). The new constraints work in both report and conformance modes, through `sink` as the other constraints do. Shapes produced by `sh:nodeByExpression` are compiled as referenced shapes: they must be IRIs or blank nodes of the shapes graph, and others are failures.
- [ ] **Step 4: Run the tests; they should pass.** Run `:rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`. Expected: PASS, with 0 known deviations left.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): sh:expression and computed sh:nodeByExpression"`

### Task 7: Documentation

**Files:**
- Modify: `docs/kastor/features/shacl-validation.md`, `docs/kastor/guides/how-to-validate-shacl.md`, `docs/kastor/getting-started/kastor-in-5-minutes.md`, `rdf/shacl/validation/README.md`, the KDoc of `UnsupportedShaclFeature.NODE_EXPRESSION` / `SHACL_FUNCTION` and `ValidationConfig.unsupportedFeatures`.

- [ ] **Step 1: Update the docs.**
  - **Supported:** node expressions (the full `shnex:` library, `sparql:` functions evaluated by the provider, custom functions); the new constraint components; and the W3C status: `EvalNodeExpr` cases run, and there are 0 known deviations.
  - **Limits:** the depth cap, the list cap and the budget.
  - **Still unsupported:** non-constant `sh:deactivated`, and SPARQL node expressions at `sh:targetWhere`.
- [ ] **Step 2: Verify.** Run the Task 6 Step 4 command. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit.** `git commit -m "docs(shacl): document SHACL 1.2 node expressions"`
