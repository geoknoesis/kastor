# Dynamic SHACL (Node Expressions §8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In Kastor's native SHACL engine, let a node expression compute, per focus node, the value of any constraint parameter whose value cannot be a blank node (SHACL 1.2 Node Expressions §8, "Dynamic SHACL"). This covers every Core parameter and the parameters of SPARQL-based constraint components.

**Architecture:**
- **Compile time.** The shapes compiler recognises a computed parameter value: a blank node at an eligible parameter that is not a SHACL list where a list is allowed. It compiles that value with the existing `NodeExpressions.compile`.
  - **Core components** are wrapped in one new constraint, `PropertyConstraint.Dynamic`, which holds each parameter as a constant or an expression.
  - **SPARQL-based components** keep `PropertyConstraint.SparqlComponent`, which gets a map of the computed parameter expressions.
- **Run time.** For each focus node, the engine evaluates the expressions with `evalNodeExpression(expr, ctx, focus, {})` and checks how many output nodes there are and what type they have. Then:
  - a Core component is rebuilt as the ordinary constant constraint through the same `ConstraintInstantiation` code the compiler uses for constants, and evaluated by the existing evaluators;
  - a SPARQL component gets the computed values pre-bound like constant parameter values.
- **What stays the same.** Constant constraints are untouched, so every fast path stays. `sh:closed`/`sh:ignoredProperties` and `sh:uniqueValuesFor`, which are not `PropertyConstraint`s, get nullable dynamic fields on `CompiledNodeShape`.

**Tech Stack:** Kotlin/JVM, JUnit 5 parameterized over `jena`/`rdf4j` (`@ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])`). Gradle only via `bash /c/tmp/kastor-gradle.sh`.

**Spec:**
- **Node Expressions §8:** `.superpowers/sdd/2026-10-24-shacl12-node-expressions/node-expr-spec.txt`, lines 4471–4718. §1.3 is at line 386.
- **Core 1.2:** `.superpowers/sdd/2026-10-24-shacl12-node-expressions/core-spec.txt`. The parameters are in §7, ill-formed graphs in §6.6.2 and list-taking parameters in §6.6.3.
- **SHACL 1.2 SPARQL:** https://www.w3.org/TR/shacl12-sparql/. Parameter declarations are in §4.2.1, pre-binding in §4.3 and Appendix A.
- **Rulings:** R1–R20 in `.superpowers/sdd/2026-10-24-shacl12-node-expressions/progress.md`.
- **What this builds on:** `docs/superpowers/plans/2026-10-24-shacl12-node-expressions.md`.

**Base:** start branch `feat/dynamic-shacl` from `feat/shacl12-node-expressions` at `79751bf2`. The reifier `sh:deactivated` wave has landed there. Line numbers below were re-checked at `79751bf2`. Locate code by symbol when they drift.

## Design decisions (each one is binding for the tasks)

**D1 — Opt-in: always on, with no `ValidationConfig` flag.** This is the user's decision.
- §1.3 makes Dynamic SHACL OPTIONAL. It says only that "processors that support it MUST follow the rules of that section".
- Every value this plan treats as dynamic is ill-formed without Dynamic SHACL:
  - Core §7.3.2: "values of sh:minInclusive … are literals";
  - Core §7.1.1: sh:class values are "IRIs or blank nodes that are well-formed SHACL lists";
  - Core §7.9.3: "Each value of sh:in … is a SHACL list";
  - SHACL-SPARQL §4.2.1: "Shapes that do not conform with the constraints declared for the parameters are ill-formed" (see D10).
- So enabling Dynamic SHACL cannot change the outcome for any well-formed shapes graph. Core §6.6.2 says a processor "SHOULD produce a failure" for such graphs.
- There is no public API change, so `checkLegacyAbi` must pass without `updateLegacyAbi`.

**D2 — Eligible Core parameters** (§8: "any parameter of a constraint can be computed … except for parameters whose values may be blank nodes").

| Group | Parameters (Kastor `DynamicComponent`) | Value shape |
|---|---|---|
| Single node | `sh:minCount`, `sh:maxCount`, `sh:minLength`, `sh:maxLength`, `sh:minInclusive`, `sh:maxInclusive`, `sh:minExclusive`, `sh:maxExclusive`, `sh:uniqueLang`, `sh:singleLine`, `sh:minListLength`, `sh:maxListLength`, `sh:uniqueMembers`, `sh:pattern`, `sh:flags`, `sh:qualifiedMinCount`, `sh:qualifiedMaxCount`, `sh:qualifiedValueShapesDisjoint`, `sh:reificationRequired`, `sh:closed` | exactly one output node |
| IRI or list (Core §6.6.3: "list-taking … when their value is a SHACL list") | `sh:class`, `sh:datatype`, `sh:nodeKind`, `sh:rootClass`, `sh:uniqueValuesFor` | the output nodes are the list members; at least one |
| Pure list | `sh:in`, `sh:languageIn`, `sh:ignoredProperties` | the output nodes are the list members; the list may be empty |

**D3 — Excluded parameters** (documented, each with its reason):
- **The value may be a blank node:**
  - shape references: `sh:node`, `sh:property`, `sh:qualifiedValueShape`, `sh:not`/`sh:and`/`sh:or`/`sh:xone`, `sh:memberShape`, `sh:someValue`, `sh:shape`, `sh:reifierShape`;
  - `sh:hasValue`, because §7.9.2 puts no syntax restriction on it;
  - property-path parameters: `sh:path`, `sh:equals`, `sh:disjoint`, `sh:lessThan`, `sh:lessThanOrEquals`, `sh:subsetOf`;
  - `sh:sparql`.
- **Already node expressions:** `sh:targetNode`, `sh:values`, `sh:defaultValue`, `sh:deactivated`, `sh:expression`, `sh:nodeByExpression`.
- **Not constraint parameters:** `sh:severity`, `sh:message`.
- **SPARQL-component parameters whose declaration does not rule out blank nodes** (D10).

**D4 — Recognition.** `DynamicParameters.value(subject, predicate, term, index)` decides as follows:
1. An IRI, a literal or a triple term is a **constant**. This is the existing path, unchanged.
2. A blank node at a list-taking parameter (D2 rows 2–3) that has an `rdf:first` or `rdf:rest` triple is a **constant list**. A malformed list is still the existing `parseRdfList` compile error. Here the constant reading and the expression reading agree: §4.1.3 says a SHACL list evaluates to its members.
3. Any other blank node is **computed**: `NodeExpressions.compile(term, index)`.
   - If that returns null (the blank node is no node expression), it throws `ShapeCompileException("<pred> value <term> of shape <subject> is neither a constant nor a node expression (SHACL 1.2 Node Expressions §8)")`. This keeps today's ill-formed failure.
   - `[]` is the empty expression, so it is computed.
   - A blank node at a single-node parameter, a list included (for example `sh:minCount ( 1 )`), is computed too: by §4.1.3 it is a list expression.

**D5 — Evaluation context.** Per §8, "evaluated in the data graph, using the current focus node":
- the focus graph is the data graph;
- the focus node is the shape's focus node, never a value node;
- the scope is empty `{}`, so `shnex:var "value"` is unbound (unlike `sh:expression`, R14);
- the shape depth is `state.depth`.

Each parameter is evaluated once per (focus node, constraint), before any value node is visited. This also holds for ASK validators, which bind `$value` per value node.

**D6 — Output rules** (the user confirmed fail-closed). §8 only says "The resulting nodes will be used as parameters". An evaluation failure is already a validation failure (§3.4).
- **Single node, including every SPARQL-component parameter:** zero output nodes is a failure, and so are two or more. This holds even for an `sh:optional` component parameter, because the expression is present, so the value is "declared".
- **IRI or list:** at least one output node, otherwise a failure.
- **Pure list:** the output nodes, in order, are the members, and zero output nodes is the empty list. Example 34 needs this: "use the resulting nodes as members of the allowed values".
- **Type:** every output must satisfy the syntax rule of its parameter. Otherwise it is a failure.
  - Counts and lengths need an `xsd:integer`-family literal ≥ 0 (`parseNonNegativeInt`).
  - Bounds need a literal.
  - Booleans need an `xsd:boolean` literal.
  - `sh:pattern` and `sh:flags` need a string literal, and a pattern must compile with `compileShaclPattern`.
  - `sh:class`, `sh:rootClass`, `sh:datatype`, `sh:uniqueValuesFor` and `sh:ignoredProperties` members need IRIs.
  - `sh:nodeKind` members need one of the four list kinds when there are several outputs, or one of the seven kinds when there is exactly one.
  - `sh:languageIn` members need string literals.
  - `sh:closed` needs an `xsd:boolean` literal or `sh:ByTypes`.
  - Triple terms obey `allowTripleTermsInShapeParameters`.
  - SPARQL-component values follow D10.
- **Failure messages** are `ShaclValidationException`s and start with `"Dynamic SHACL: "`. They name the parameter, the shape, the focus node and either the count ("produced N nodes; exactly one is required" or "produced no nodes; …") or the bad node.

**D7 — Model.**
- **Core: re-instantiation per focus node, not a parameter holder in every constraint.** `PropertyConstraint.Dynamic` resolves to the ordinary constraint, which the existing `evaluateConstraint` then evaluates.
- **Instance cache.** Instances are cached per run in a `DynamicInstanceCache`, keyed by the constraint's identity plus the resolved terms. Without it, a `Pattern` would be recompiled for every focus node and `ctx.patternOutcomes` (keyed by `Pattern` identity) would grow per focus node. The cap is `DYNAMIC_INSTANCE_CACHE_MAX = 4096`. When the cache is full, new instances are built but not stored, and existing entries are never evicted.
- **Reifier deactivation.** `compileConstraints` already passes every constraint through its local `add(c, vararg triples)` (ShapesCompiler.kt:1229). That helper wraps a constraint in `PropertyConstraint.Deactivatable` when a reifier of one of its parameter triples deactivates it. A `Dynamic` goes through the same `add(...)` with its parameter triples, so the nesting is `Deactivatable(Dynamic(…))`. Deactivation is checked first, then the parameters are resolved.

**D8 — Reports.**
- An instantiated Core constraint reports exactly like a constant one:
  - its Core `sh:sourceConstraintComponent`;
  - `sh:value`;
  - the shape's `sh:message`, or else a default message that already quotes the resolved value.
- No `sh:sourceConstraint` is added.
- `sh:datatype`'s triple-annotated severity and messages (`annotatedConstraintSeverity`/`annotatedConstraintMessages`) are keyed on the triple, whose object is the expression blank node, so they still apply.
- **SPARQL components:** the computed values join the pre-bound map passed to `solutionViolation`. `{$param}` in `sh:message` therefore shows the computed value.

**D9 — Recursion, narrowing, bounds.**
- **Recursion.** Parameter expressions are expressions the shape evaluates. `ShapesCompiler.constraintExpressions(c): List<NodeExpression>` (ShapesCompiler.kt:938) and `shapeExpressions(cn)` (:574) are the single collection points for the shape closure, the SCC graph and `ExpressionShapes.of`. Expression reads of shapes are already recorded as negative, so R16 applies: a data cycle through them is UNDEFINED, and so a failure.
- **Narrowing.** `targetWhereCandidates` (NativeTargetPlanning.kt:94) narrows only on constant `Class`, `NodeKind` and `Datatype` instances. Like `Deactivatable`, a `Dynamic` matches none of them, and the constant constraints still hold alongside it, so narrowing stays sound. A regression test pins this.
- **Bounds:**
  - outputs are capped by `maxPathValueNodes` and tick the budget per node;
  - each instantiation ticks `ctx.budget.tick("dynamic constraint")`;
  - nesting is capped by `MAX_NODE_EXPRESSION_DEPTH`;
  - provider timeouts and cancellation propagate and are never turned into failures.
- **Recursion polarity** is computed at run time on the instantiated constraint (`negative = c.max != null` in `evaluateQualified`).

**D10 — SPARQL-based constraint component parameters.**
- **Rule.** A parameter is dynamic when its declaration rules out blank nodes. SHACL-SPARQL §4.2.1:
  - "The class sh:Parameter is defined as a SHACL subclass of sh:PropertyShape";
  - "all properties that are applicable to property shapes may also be used for parameters. This includes … constraint parameters such as sh:class";
  - "Shapes that do not conform with the constraints declared for the parameters are ill-formed."
  
  So a declaration that every blank-node value violates makes a blank-node value ill-formed as a constant, and §8's reason for excluding the parameter ("cannot be distinguished from a node expression") no longer applies.
- **Blank-excluding declarations.** A declaration has this property when it has a **constant** value, in its own triples, for at least one of the following Core constraints that, by Core's textual definitions, every blank-node value node violates:

| Declaration constraint | Why a blank node violates it (Core 1.2) |
|---|---|
| `sh:nodeKind` an IRI in {`sh:IRI`, `sh:Literal`, `sh:IRIOrLiteral`, `sh:TripleTerm`}, or a list without `sh:BlankNode` | §7.1.3 |
| `sh:datatype` (any) | §7.1.2: "each value node that is not a literal … there is a validation result" |
| `sh:in` whose list has no blank-node member | §7.9.3 |
| `sh:pattern` | §7.4.3: "each value node that is a blank node or …" |
| `sh:minLength`, `sh:maxLength` | §7.4.1/§7.4.2: "… or where v is a blank node" |
| `sh:minInclusive`, `sh:maxInclusive`, `sh:minExclusive`, `sh:maxExclusive` | §7.3: "$minInclusive <= v does not return true" for a blank node |
| `sh:languageIn` | §7.4.5: "not a literal" |
| `sh:rootClass` | §7.9.4: "not an IRI" |

  These do **not** qualify:
  - `sh:class`, since a SHACL instance can be a blank node (§7.1.1). A "non-blank-node meaning" of a class cannot be read from the declaration;
  - `sh:hasValue`, `sh:singleLine`, shape references, and any declaration constraint that is itself a node expression.
- **Lists.** A blank-excluding declaration also rules out SHACL lists, so D4 rule 2 never applies to component parameters. Every blank-node value is computed, or else it is a compile error.
- **Output check.** Each computed output must be exactly one node (D6) and must not be a blank node. It must also satisfy the declaration's `sh:nodeKind` and `sh:datatype`, if present. The other declared constraints are not checked, as for constants today: §4.2.1 says implementations "MAY use these constraint parameters".
- **Pre-binding** (§4.3: "each value of a parameter … MUST be pre-bound"). The engine pre-binds by text substitution:
  - `SparqlQueryTemplate.bind` replaces the variable slots with `renderTerm` text;
  - a value `renderTerm` cannot render (a triple term, a directional string) goes to provider initial bindings;
  - the Appendix A restrictions (no `MINUS`, no `VALUES`/`AS ?var` on pre-bound variables) are checked once, on the validator text, by `compileRestricted`. They do not depend on values.
- **New trust boundary** (ruling D8). Constant parameter values come from the shapes graph. Computed values come from the **data graph**. They are written into the query text with the same hardened escaping as `$this` and `$value`, which are data-graph values too: `escape` doubles every `\`, writes a `u`/`U` after a data backslash as `u`/`U`, and escapes quotes and control characters, so the `\uXXXX` pre-decoding of Jena and RDF4J never decodes data. There is no separate provider-only path and no `providerOnly` set: it would leave the identical channel open one variable over (`$value`), and the providers' own binding substitution is a second renderer (a text rewrite on RDF4J, different slot semantics on Jena). Task 6 tests the rendering under three decoding views and, on both providers, against an oracle. A language-tagged value in a plain slot is followed by a space.
- **Query cache.** No parsed-query or query-text cache exists: `NativeValidationContext.select` builds a `SandboxedSelectQuery` per call. Each template is compiled once per component use and rendered per focus node anyway, because `$this` is substituted. Computed values therefore add no cache layer and invalidate nothing. Their only cost is one expression evaluation per (focus node, component use), reused across the value nodes of an ASK validator. `MAX_CONSTRAINTS_PER_COMPONENT_USE = 1024` still bounds the combinations, counting each expression value as one value.

## Global Constraints

- **Gradle:** only `bash /c/tmp/kastor-gradle.sh <tasks>`. Exit code 75 means the lock is busy: retry. Never call `./gradlew`. Keep each invocation under ~9 minutes.
- **Final verification:** `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`. All must pass. `checkLegacyAbi` must pass **without** `updateLegacyAbi` (D1). If a task needs a public change, stop and ask.
- **Commits:** every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never stage or commit `.superpowers/`.
- **No publishing, no tags, no pushes.**
- **Tests:** every task's tests run on both providers (`jena`, `rdf4j`) wherever a SPARQL expression (`sparql:` function, `sh:select`, `sh:sparqlExpr`), a SPARQL validator or a provider is involved. Each fail-closed rule gets a **mutation run**: weaken the guard, check that the named test fails, then restore it. Never commit the mutation.
- **Docs:** update `docs/kastor/features/shacl-validation.md` and `CHANGELOG.md` (`## [Unreleased]` → `### Added`) in Task 7. Earlier tasks must not leave docs that contradict the code.
- **Existing rulings stay in force.** R5: "true" means the term `"true"^^xsd:boolean`. R16: non-monotone expression reads, and data cycles through them, are a failure. R8: candidate shapes compile tolerantly. A failed evaluation is a validation failure (§3.4). Reifier deactivation goes through `add(...)`/`Deactivatable`.
- **Constant parameters keep their current behaviour and messages,** including today's lenient constant parsing. The D6/D10 type rules apply only to computed outputs.

## Review Focus

1. **Focus node vs value node.** A parameter expression on a property shape reads the *focus* node, and `value` is unbound (D5). Pinned in Task 1 (`dynamic parameters read the focus node, not the value nodes`).
2. **The data has several values where the author expected one.** The run must fail, not pick one. Pinned in Task 1 (`several output nodes for a single-node parameter fail`) and Task 6 (`a component parameter with several outputs fails`).
3. **The dynamic constraint is checked only for conformance** (inside `sh:not`, `sh:node`, `sh:or`, `shnex:filterShape`). A failure must propagate, not count as "does not conform". Pinned in Task 1 (`a dynamic failure inside sh:not stays a failure`).
4. **The shapes graph is validated against itself** (`validate(g, g)`), with constant blank-node lists at `sh:in`, a blank-node `sh:hasValue`, and a blank-node value of a component parameter whose declaration has only `sh:class`. They must keep their constant meaning. Pinned in Task 3 (`constant lists and blank-node sh:hasValue keep their meaning when shapes are data`) and Task 6 (`a parameter declared with only sh:class keeps a blank-node constant`).
5. **Hostile data drives the parameter:** a huge `sh:in` list, a catastrophic `sh:pattern`, or a SPARQL-injection string read from the data into a component parameter. This must stay bounded, and must never change the query structure. Pinned in Task 3 (`an sh:in expression larger than maxPathValueNodes fails`), Task 4 (`a pattern read from the data is bounded by the pattern budget`, `the dynamic instance cache stops growing at its cap`) and Task 6 (`hostile computed values cannot change the validator query`).

---

### Task 1: Dynamic constraint model and single-node property parameters

**Files:**
- Create: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/native/DynamicParameters.kt`
- Create: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/native/ConstraintInstantiation.kt`
- Create: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/providers/NativeDynamicConstraints.kt`
- Modify: `…/shacl/native/ShapesCompiler.kt`:
  - add `PropertyConstraint.Dynamic` to the sealed class (starts at :73, next to `Deactivatable` at :193);
  - in `compileConstraints` (:1203), route the scalar parameters through `DynamicParameters.value` and `ConstraintInstantiation`, always via `add(...)`;
  - move `parseNonNegativeInt` (:1634) and `literalString` (:1628) to `ConstraintInstantiation`.
- Modify: `…/shacl/providers/NativePropertyConstraints.kt`, `evaluateConstraint` dispatch (:30–105).
- Modify: `…/shacl/providers/NativeShapeEvaluation.kt`, `provisionalType` (:260).
- Modify: `…/shacl/providers/NativeValidationContext.kt`: add `dynamicInstances`.
- Test: create `rdf/shacl/validation/src/test/kotlin/com/geoknoesis/kastor/rdf/shacl/DynamicShaclTest.kt`. Copy the harness (prefixes, `g`, `ex`, `int`, `repository`, `validate`, `validateSelf`) from `NodeExpressionConstraintTest`.

**Interfaces:**
- Produces, in `native/DynamicParameters.kt`:
  ```kotlin
  internal sealed interface ParameterValue {
      data class Constant(val term: RdfTerm) : ParameterValue
      data class Computed(val node: BlankNode, val expr: NodeExpression) : ParameterValue
  }
  internal enum class ParameterShape { SINGLE, IRI_OR_LIST, LIST }
  internal enum class DynamicComponent(val type: ConstraintType, val parameters: List<Iri>) {
      MIN_COUNT, MAX_COUNT, MIN_LENGTH, MAX_LENGTH, MIN_INCLUSIVE, MAX_INCLUSIVE, MIN_EXCLUSIVE, MAX_EXCLUSIVE,
      UNIQUE_LANG, SINGLE_LINE, MIN_LIST_LENGTH, MAX_LIST_LENGTH, UNIQUE_MEMBERS,
      // Task 3: CLASS, DATATYPE, NODE_KIND, ROOT_CLASS, IN, LANGUAGE_IN
      // Task 4: PATTERN (sh:pattern, sh:flags), QUALIFIED (qualifiedMinCount, qualifiedMaxCount, qualifiedValueShapesDisjoint), REIFICATION (sh:reificationRequired)
  }
  internal object DynamicParameters {
      fun shapeOf(predicate: Iri): ParameterShape?          // null = not eligible (D3)
      fun value(subject: RdfResource, predicate: Iri, term: RdfTerm, index: ShapeGraphIndex): ParameterValue   // D4
      val predicates: Set<Iri>                                // every eligible Core predicate (D2), for Task 2's reachability
  }
  ```
- Produces, in `ShapesCompiler.kt`:
  ```kotlin
  data class Dynamic(
      val component: DynamicComponent,
      val parameters: Map<Iri, ParameterValue>,   // absent optional parameters (e.g. sh:flags) are not keys
      val extras: ComponentExtras = ComponentExtras(),
  ) : PropertyConstraint() { val expressions: List<NodeExpression> }   // the Computed exprs, in parameter order
  ```
- Produces, in `native/ConstraintInstantiation.kt`:
  ```kotlin
  internal data class ComponentExtras(
      val severityOverride: ViolationSeverity? = null, val messages: List<Literal> = emptyList(),
      val qualifiedShape: RdfResource? = null, val siblings: List<RdfResource> = emptyList(),
      val reifierShape: RdfResource? = null,
  )
  internal object ConstraintInstantiation {
      /** Builds the constant constraint; throws ShapeCompileException on an ill-typed value. Null = no constraint. */
      fun instantiate(component: DynamicComponent, values: Map<Iri, List<RdfTerm>>, extras: ComponentExtras, config: ValidationConfig): PropertyConstraint?
      /** D6 type rule of [parameter] for one computed output; returns the reason it fails, or null. */
      fun outputError(parameter: Iri, node: RdfTerm, outputs: Int): String?
      fun parseNonNegativeInt(term: RdfTerm, role: String): Int   // moved, unchanged
      fun literalString(term: RdfTerm): String?                   // moved, unchanged
  }
  ```
- Produces, in `providers/NativeDynamicConstraints.kt`:
  ```kotlin
  internal const val DYNAMIC_INSTANCE_CACHE_MAX = 4096
  internal class DynamicInstanceCache(private val max: Int = DYNAMIC_INSTANCE_CACHE_MAX) {
      fun getOrPut(constraint: PropertyConstraint.Dynamic, values: List<List<RdfTerm>>, build: () -> PropertyConstraint?): PropertyConstraint?
      val size: Int
  }
  internal fun NativeShaclValidator.evaluateComputed(expr: NodeExpression, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): List<RdfTerm>   // D5
  internal fun NativeShaclValidator.resolveDynamic(c: PropertyConstraint.Dynamic, focus: RdfTerm, ctx: NativeValidationContext, depth: Int, shape: RdfResource): PropertyConstraint?
  ```
  - `DynamicInstanceCache` keys are the constraint by identity (an `IdentityHashMap` per constraint) plus `values`. When `size == max`, `build()` runs and its result is not stored.
  - `resolveDynamic` applies D6 (count, then `outputError`), then `instantiate`, wrapping a `ShapeCompileException` as `ShaclValidationException("Dynamic SHACL: …", e)`, and caches the result.
- Produces, in `NativeValidationContext`: `val dynamicInstances = DynamicInstanceCache()`.

- [ ] **Step 1: Write the failing tests** in `DynamicShaclTest`. Every test that uses a `sparql:` function is parameterized over `["jena", "rdf4j"]`.
  ```kotlin
  // §8.1 Example 31, verbatim shapes; data:
  // ex:Old a ex:President; ex:age 40; ex:country ex:USA .  ex:Young a ex:President; ex:age 34; ex:country ex:USA .
  // ex:Fr a ex:President; ex:age 20; ex:country ex:France . ex:Kid a ex:President; ex:age 17; ex:country ex:France .
  // ex:None a ex:President; ex:age 17 .   (no country: sparql:equals not applied → if takes else → 18)
  fun `spec example 31 computes the minimum age of presidents`(provider)
      // violations focus = {Young, Kid, None}; each MIN_INCLUSIVE with value its age; Old and Fr conform
  fun `dynamic parameters read the focus node, not the value nodes`(provider)
      // sh:path ex:item ; sh:maxCount [ shnex:pathValues ex:maxItems ] ; focus ex:maxItems 1 ; two items each with ex:maxItems 5
      // → one MAX_COUNT violation on the focus; and sh:maxCount [ shnex:var "value" ] → assertThrows ShaclValidationException
  fun `zero output nodes for a single-node parameter fail`(provider)
      // sh:minCount [ shnex:pathValues ex:min ] with no ex:min → ShaclValidationException; message contains "Dynamic SHACL", "sh:minCount", "no nodes"
  fun `several output nodes for a single-node parameter fail`(provider)
      // sh:minInclusive [ shnex:pathValues ex:minAge ], focus has ex:minAge 18, 35 → throws, message contains "2 nodes"
  fun `ill-typed outputs fail`(provider)
      // each of: sh:minCount "abc", sh:minCount -1, sh:minLength ex:iri, sh:minInclusive ex:iri, sh:uniqueLang "yes", sh:maxListLength 1.5
      //   delivered via [ shnex:pathValues ex:p ] from the focus → assertThrows(ShaclValidationException) per case
  fun `a dynamic failure inside sh:not stays a failure`(provider)
      // ex:S sh:targetNode ex:a ; sh:not [ sh:property [ sh:path ex:p ; sh:minCount [ shnex:pathValues ex:none ] ] ] → throws
  fun `a blank node that is no node expression at a dynamic parameter is a compile error`()
      // sh:minCount [ ex:foo 1 ] → error.cause is ShapeCompileException, message contains "neither a constant nor a node expression"
  fun `a reifier deactivates a dynamic parameter triple`()
      // ex:PS sh:minCount _:e {| sh:deactivated true |} where _:e is an expression yielding 5 → no MIN_COUNT; with
      //   {| sh:deactivated [ shnex:pathValues ex:skip ] |} → skipped only for foci with ex:skip true
  fun `constant scalar parameters behave as before`()
      // sh:minCount 1 / sh:maxInclusive 10 on a fixed dataset: same violations as before (pins the fast path)
  fun `every single-node property parameter accepts an expression`(provider)
      // table over MIN_COUNT..UNIQUE_MEMBERS: parameter = [ shnex:if [ sparql:bound ( [ shnex:pathValues ex:strict ] ) ] ; shnex:then <strict> ; shnex:else <lax> ]
      //   a focus with ex:strict gets the violation of <strict>, one without conforms; assert the ConstraintType
  ```
- [ ] **Step 2: Run them and confirm they fail.**
  Run: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests 'com.geoknoesis.kastor.rdf.shacl.DynamicShaclTest'`
  Expected: FAIL. Today the blank node either becomes a compile error or `MinInclusive(<bnode>)`.
- [ ] **Step 3: Implement `DynamicParameters`, `ConstraintInstantiation` (Task 1 components) and `PropertyConstraint.Dynamic`, and route the 13 scalar parameters of `compileConstraints` through them.**
  - The constant path calls `instantiate` with `mapOf(pred to listOf(term))`, and must yield the same objects as today.
  - A `Computed` value yields `Dynamic`, passed through `add(dynamic, pred to term)`.
  - `sh:minCount`/`sh:maxCount` keep the `PROPERTY_SHAPE` flavour gate.
- [ ] **Step 4: Implement `resolveDynamic` and its dispatch.** The dispatch is `is PropertyConstraint.Dynamic -> resolveDynamic(c, focus, ctx, state.depth, currentShape)?.let { evaluateConstraint(focus, tpl, values, it, ctx, state, currentShape, path, sink) }`. `provisionalType(Dynamic)` returns `c.component.type`.
- [ ] **Step 5: Run the tests and confirm they pass.** Same command. Expected: PASS on both providers.
- [ ] **Step 6: Mutation runs.**
  - (a) In `resolveDynamic`, accept zero outputs as "no constraint". `zero output nodes…` must FAIL.
  - (b) Take the first of several outputs. `several output nodes…` must FAIL.
  - (c) Skip `outputError`. `ill-typed outputs fail` must FAIL.
  - (d) Add `Dynamic` without going through `add(...)`. `a reifier deactivates a dynamic parameter triple` must FAIL.
  - Restore after each.
- [ ] **Step 7: Run the whole module test task.**
  Run: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test`. Expected: PASS.
- [ ] **Step 8: Commit.**
  ```bash
  git add rdf/shacl/validation/src
  git commit -m "feat(shacl): Dynamic SHACL for single-node property parameters (Node Expressions §8)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```

### Task 2: Parameter expressions in the shape closure, recursion analysis and detection

**Files:**
- Modify: `…/shacl/native/ShapesCompiler.kt`:
  - in `constraintExpressions` (:938), add `is PropertyConstraint.Dynamic -> c.expressions`. `Deactivatable` already recurses into its constraint;
  - in `expressionPositions` (:796), add `DynamicParameters.predicates`, so that `shapeReachableNodes` (:778) and feature detection walk the shapes of parameter expressions.
- Test: `DynamicShaclTest.kt`.

**Interfaces:**
- Consumes `Dynamic.expressions` and `DynamicParameters.predicates` (Task 1).
- Nothing new is produced. Later tasks only extend `constraintExpressions` (Task 6), `shapeExpressions(cn)` (:574, Task 5) and `expressionPositions` (Task 6).

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  fun `a parameter expression's shapes are compiled`(provider)
      // sh:maxCount [ shnex:count [ shnex:filterShape ex:Big ; shnex:nodes [ shnex:pathValues ex:part ] ] ], ex:Big reachable only from it → validates, counts as expected
  fun `a data cycle through a parameter expression is a failure`()
      // ex:S sh:property [ sh:path ex:p ; sh:minCount [ shnex:count [ shnex:filterShape ex:S ; shnex:nodes [ shnex:pathValues ex:p ] ] ] ]
      //   data ex:a ex:p ex:a → ShaclValidationException (R16)
  fun `an acyclic recursive read through a parameter expression is decided`()      // same shapes, ex:a ex:p ex:b → no exception, expected result
  fun `an unsupported feature inside a shape reached only through a parameter expression is detected`()
      // ex:Hidden has sh:target […]; only referenced by shnex:filterShape at sh:minCount → UnsupportedShaclFeatureException under FAIL
  fun `a dynamic parameter in a recursive shape reports provisional answers as undefined, not violations`()
  ```
- [ ] **Step 2: Run them and confirm they fail.** Expected: FAIL, because the shape is not compiled or not detected.
- [ ] **Step 3: Implement the two edits.**
- [ ] **Step 4: Run them and confirm they pass.**
- [ ] **Step 5: Mutation run.** Remove the `Dynamic` branch from `constraintExpressions`. The cycle test and the detection test must FAIL. Restore.
- [ ] **Step 6: Commit.** Message: `feat(shacl): include Dynamic SHACL parameter expressions in shape closure and recursion analysis`, ending with the Co-Authored-By line.

### Task 3: List-taking parameters (`sh:in`, `sh:languageIn`, `sh:class`, `sh:datatype`, `sh:nodeKind`, `sh:rootClass`)

**Files:**
- Modify: `DynamicParameters.kt` and `ConstraintInstantiation.kt`. Add `IN`, `LANGUAGE_IN`, `CLASS`, `DATATYPE`, `NODE_KIND` and `ROOT_CLASS`.
- Modify: `ShapesCompiler.kt` `compileConstraints`, the `sh:datatype`, `sh:class`, `sh:nodeKind`, `sh:in`, `sh:languageIn` and `sh:rootClass` blocks (from :1236). Route them through `value` and `instantiate`, keeping `ensureShapeTermAllowed`, the triple-annotated severity and messages of `sh:datatype`, and `add(...)`.
- Modify: `providers/NativeDynamicConstraints.kt`. Apply the D6 list rules.
- Test: `DynamicShaclTest.kt`.

**Interfaces:**
- For `CLASS`/`DATATYPE`/`NODE_KIND`/`ROOT_CLASS`, `instantiate` takes `values[pred]` as the member set. One member builds `Class`; several build `ClassAnyOf`, `Datatype(allowed)`, `NodeKind(kinds)` or `RootClass(roots)`.

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  // §8.2 Examples 32–34 verbatim (sh:ShapeClass implicit target) + ex:Bad a ex:Address; ex:country ex:USA; ex:state "QLD"
  fun `spec example 34 computes the allowed states from the country`(provider)     // single IN violation, focus ex:Bad, value "QLD"
  fun `an sh:in expression with no output rejects every value`(provider)           // ex:country ex:Nowhere → IN violation
  fun `dynamic sh:class takes the outputs as alternatives`(provider)               // allowedType ex:A, ex:B ; typed ex:B conforms, ex:C → CLASS
  fun `dynamic sh:class, sh:datatype, sh:nodeKind and sh:rootClass need at least one output`(provider)   // each throws, "no nodes"
  fun `dynamic list members must be well-typed`(provider)                          // class→literal, datatype→bnode, nodeKind→ex:Foo, nodeKind (2 outputs incl. sh:IRIOrLiteral), languageIn→IRI → each throws
  fun `dynamic sh:datatype keeps the severity annotated on its triple`()           // reifier sh:severity sh:Warning on the expression triple → WARNING
  fun `constant lists and blank-node sh:hasValue keep their meaning when shapes are data`(provider)   // validateSelf: sh:in ( ex:a ex:b ), sh:hasValue _:x → same results as today
  fun `an sh:in expression larger than maxPathValueNodes fails`(provider)          // maxPathValueNodes = 3, 4 outputs → throws, mentions maxPathValueNodes
  fun `targetWhere narrowing ignores dynamic value-type constraints`()             // membership shape with only a dynamic sh:nodeKind: IRI and literal candidates both considered
  ```
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement the six components and the list rules.**
- [ ] **Step 4: Run them and confirm they pass** on both providers.
- [ ] **Step 5: Mutation runs.**
  - (a) Treat zero outputs at `IRI_OR_LIST` as "no constraint". `…need at least one output` must FAIL.
  - (b) Make `targetWhereCandidates` narrow on `Dynamic(NODE_KIND)` as `NodeKind(sh:IRI)`. The narrowing test must FAIL.
  - Restore after each.
- [ ] **Step 6: Run the W3C regression.**
  Run: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:w3cConformanceTest`. Expected: PASS. The malformed-list core cases must still fail, through D4 rule 2.
- [ ] **Step 7: Commit.** Message: `feat(shacl): Dynamic SHACL for sh:in, sh:languageIn and IRI-or-list parameters`, ending with the Co-Authored-By line.

### Task 4: Multi-parameter components (`sh:pattern`/`sh:flags`, qualified counts, `sh:reificationRequired`)

**Files:**
- Modify: `DynamicParameters.kt` and `ConstraintInstantiation.kt`. Add `PATTERN`, `QUALIFIED` and `REIFICATION`.
- Modify: `ShapesCompiler.kt` `compileConstraints`:
  - **Pattern:** each `sh:pattern` value pairs with the shape's single `sh:flags`. If either is computed, the result is `Dynamic(PATTERN, {pattern, flags?})`.
  - **Qualified** (block ending ~:1397): `qualifiedValueShape` stays constant and goes in `extras.qualifiedShape`. `siblings` are computed whenever `sh:qualifiedValueShapesDisjoint` is present. When min and max have different reifier deactivation, the existing split into two `Qualified` constraints stays, and each half is a `Dynamic` over its own parameters.
  - **Reification** (:1399–1409): `instantiate(REIFICATION, …)` builds `ReifierShape(extras.reifierShape!!, required)` when there is a reifier shape, else `ReificationRequired(required)`. This mirrors the current split.
  - **Detection** (`unsupportedFeature` :872, condition at :896): a computed `sh:reificationRequired` on a non-predicate path is reported like the constant `true` (`REIFIER_CONSTRAINT_ON_COMPLEX_PATH`).
- Modify: `NativeShapeEvaluation.kt` `validatePropertyShape`, reifier block (:313–334). The filter also accepts `Dynamic(REIFICATION)` after `unwrapped()`. Inside the `active` mapping, after the `Deactivatable` check, a `Dynamic` is resolved with `resolveDynamic` for the focus node.
- Test: `DynamicShaclTest.kt`.

**Interfaces:**
- `instantiate(PATTERN, …)` returns `PropertyConstraint.Pattern(pattern, flags, compileShaclPattern(pattern, flags))`. A pattern that does not compile is a `ShapeCompileException`, which `resolveDynamic` wraps as a failure.

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  fun `dynamic sh:pattern reads the pattern from the data`(provider)       // "^[0-9]+$" → "12a" violates (PATTERN)
  fun `dynamic sh:flags applies to a constant pattern`(provider)           // "ABC" vs "^abc$" conforms only when flags "i"
  fun `an invalid dynamic pattern fails`(provider)                          // "(" → ShaclValidationException
  fun `a pattern read from the data is bounded by the pattern budget`()     // "(a+)+$" vs 40×"a"+"!", patternTimeout 50 ms → undecided-pattern result per existing semantics, run ends promptly
  fun `the dynamic instance cache stops growing at its cap`()               // DynamicInstanceCache(max = 4): 10 keys → size 4; cached key → identical instance; uncached → build each time
  fun `dynamic qualified counts`(provider)                                  // qualifiedMinCount [ shnex:pathValues ex:minQ ] → QUALIFIED_MIN_COUNT below it
  fun `dynamic qualifiedMaxCount in a recursive shape stays non-monotone`()  // cycle with dynamic max → failure (R16)
  fun `dynamic sh:reificationRequired with and without sh:reifierShape`(provider)   // missing reifier reported (REIFIER_SHAPE resp. REIFICATION_REQUIRED) only when true
  fun `dynamic sh:reificationRequired on a complex path is reported`()      // sh:path ( ex:a ex:b ) → UnsupportedShaclFeatureException
  ```
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass** on both providers.
- [ ] **Step 5: Mutation runs.**
  - (a) Remove the `size == max` guard in `DynamicInstanceCache.getOrPut`. The cache test must FAIL.
  - (b) Remove the complex-path detection. Its test must FAIL.
  - Restore after each.
- [ ] **Step 6: Commit.** Message: `feat(shacl): Dynamic SHACL for pattern, qualified count and reification parameters`, ending with the Co-Authored-By line.

### Task 5: Node-shape parameters `sh:closed`/`sh:ignoredProperties` and `sh:uniqueValuesFor`

**Files:**
- Modify: `ShapesCompiler.kt`:
  - in `CompiledNodeShape` (:250), add `val closedParameter: ParameterValue.Computed? = null`, `val ignoredParameter: ParameterValue.Computed? = null` and `val uniqueValuesForParameter: ParameterValue.Computed? = null`;
  - in `compileNodeShape` (:1014; closed at :1095), classify through `DynamicParameters.value`;
  - add the three expressions to `shapeExpressions(cn)` (:574).
- Modify: `providers/NativeAggregateConstraints.kt`:
  - `validateClosed` (:17) takes the mode and the ignored set from `resolveClosed`;
  - `validateUniqueValuesForShape` (:101) groups the focus nodes by their resolved property list.
- Modify: `NativeShapeEvaluation.kt` `validateNodeShape` (:90). The closed check runs when `shape.closed != NONE || shape.closedParameter != null`. Keep the `reifiers.closed` deactivation check first.
- Modify: `NativeTargetPlanning.kt` `countConstraintEvaluationSlots` (:117). Count a dynamic closed check.
- Test: `DynamicShaclTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  internal fun NativeShaclValidator.resolveClosed(focus: RdfResource, shape: CompiledNodeShape, ctx: NativeValidationContext, depth: Int): Pair<ClosedMode, Set<Iri>>
  internal fun NativeShaclValidator.resolveUniqueValuesFor(focus: RdfTerm, shape: CompiledNodeShape, ctx: NativeValidationContext): List<Iri>
  ```
- Closed resolution follows D6: `true` → `TRUE`, `false` → `NONE`, `sh:ByTypes` → `BY_TYPES`, anything else is a failure. `ignoredProperties` is a `LIST` of IRIs.
- Unique values (§7.9.5): for every focus V, V is reported when another target node has the same values for all of V's resolved properties. Building keys costs one pass over the targets per distinct resolved list, with a budget tick per key. Per-focus deactivation is respected as it is today.

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  fun `dynamic sh:closed`(provider)                 // [ shnex:if [ sparql:bound ( [ shnex:pathValues ex:strict ] ) ] ; shnex:then true ; shnex:else false ] → CLOSED only for strict foci
  fun `dynamic sh:closed rejects other values`()    // → "maybe" → throws
  fun `dynamic sh:ignoredProperties`(provider)      // sh:closed true ; sh:ignoredProperties [ shnex:pathValues ex:extraAllowed ]
  fun `dynamic sh:uniqueValuesFor`(provider)        // per-focus property lists; duplicates detected per focus's own list
  fun `validateClosedShapes=false also skips dynamic sh:closed`()
  ```
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass** on both providers.
- [ ] **Step 5: Mutation run.** Make `resolveClosed` map an unknown value to `NONE`. `…rejects other values` must FAIL. Restore.
- [ ] **Step 6: Commit.** Message: `feat(shacl): Dynamic SHACL for sh:closed, sh:ignoredProperties and sh:uniqueValuesFor`, ending with the Co-Authored-By line.

### Task 6: SPARQL-based constraint component parameters

**Files:**
- Modify: `…/shacl/native/SparqlConstraintComponents.kt`:
  - in `checkedParameters` (:119) and `Parameter` (:29), add `blankExcluding: Boolean` per D10, computed from the declaration node's constant triples. Also add `kinds: List<Iri>` and `datatype: Iri?` (the declaration's `sh:nodeKind` and `sh:datatype`) for the output check;
  - in `compile` (:67, combinations at :88–115), a `BlankNode` value of a `blankExcluding` parameter becomes a computed entry. It compiles with `NodeExpressions.compile`. When that returns null, it throws `ShapeCompileException("… is neither a constant nor a node expression; parameter <decl> rules out blank nodes (SHACL 1.2 SPARQL §4.2.1)")`;
  - add `fun dynamicParameterPaths(): Set<Iri>`: the paths of `blankExcluding` parameters of every component.
- Modify: `ShapesCompiler.kt`:
  - `PropertyConstraint.SparqlComponent` (:164): add `val computed: Map<String, ComputedComponentParameter> = emptyMap()`. A computed parameter is not a key of `parameters`;
  - `constraintExpressions` (:938): add `is SparqlComponent -> c.computed.values.map { it.expr }`;
  - `shapeReachableNodes` (:778): also walk `index.sparqlComponents.dynamicParameterPaths()` as expression positions.
- Modify: `…/shacl/native/SparqlQueryTemplate.kt`. `bind` (:165) gets `providerOnly: Set<String> = emptySet()`. A name in it skips text rendering and goes to `providerBindings`, both in `Slot` and in `BoundCheck`.
- Modify: `…/shacl/providers/NativeSparqlComponents.kt` `evaluateSparqlComponent` (:23). It takes `computed: Map<String, RdfTerm>` and merges it into `preBound`, passes `providerOnly` = the computed names whose value fails `renderableFromData` to every `bind`, and uses the merged map for `solutionViolation` (D8).
- Modify: `NativePropertyConstraints.kt` dispatch (:103). `is SparqlComponent -> evaluateSparqlComponent(…, computed = resolveComponentParameters(c, focus, ctx, state.depth))`.
- Modify: `providers/NativeDynamicConstraints.kt`. Add `resolveComponentParameters`.
- Test: create `rdf/shacl/validation/src/test/kotlin/com/geoknoesis/kastor/rdf/shacl/DynamicSparqlComponentTest.kt`, with the same harness as `SparqlConstraintComponentTest`.

**Interfaces:**
- Produces:
  ```kotlin
  internal data class ComputedComponentParameter(val node: BlankNode, val expr: NodeExpression, val kinds: List<Iri>, val datatype: Iri?)
  internal fun NativeShaclValidator.resolveComponentParameters(c: PropertyConstraint.SparqlComponent, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): Map<String, RdfTerm>
  internal fun renderableFromData(term: RdfTerm): Boolean   // SparqlQueryTemplate.renderTerm(term) != null && no '\\' in IRI / lexical form (D10)
  ```
- `resolveComponentParameters` uses `evaluateComputed` (Task 1). It requires exactly one output, not a blank node, matching `kinds` (via the existing node-kind test) and `datatype` (via the existing datatype match). Anything else is `ShaclValidationException("Dynamic SHACL: …")`.

- [ ] **Step 1: Write the failing tests.** Every test is parameterized over `["jena", "rdf4j"]` unless marked.
  ```kotlin
  // ex:MaxAgeComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:maxAge ; sh:datatype xsd:integer ] ;
  //   sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER ($value <= $maxAge) }" ] .
  // shape: sh:path ex:age ; ex:maxAge [ shnex:if … shnex:then 65 ; shnex:else 70 ]
  fun `an ASK component parameter is computed per focus node`(provider)        // ages 66 (then-branch focus) violate, 66 (else-branch focus) conform; SPARQL_CONSTRAINT_COMPONENT
  fun `a SELECT component parameter is computed and shown in the message`(provider)
      // SELECT validator "SELECT $this ?value WHERE { $this $PATH ?value FILTER (?value > $maxAge) }" ; sh:message "more than {$maxAge}" → message "more than 65"
  fun `the computed value is evaluated once per focus, not per value node`(provider)
      // ASK validator, 3 value nodes; expression counts via a custom-function body ticking a test counter (or ctx.counters) → 1 evaluation per focus
  fun `a component parameter with no output, several outputs, a blank node or the wrong datatype fails`(provider)   // 4 cases → ShaclValidationException each
  fun `a parameter declared with only sh:class keeps a blank-node constant`(provider)
      // declaration [ sh:path ex:ref ; sh:class ex:C ] ; shape ex:ref _:b (a data node, validateSelf) → today's behaviour (pre-bound _:b), no expression compile
  fun `each blank-excluding declaration constraint makes the parameter dynamic`()   // table over D10 rows: nodeKind sh:IRI, list (sh:IRI sh:Literal), datatype, in (1 2), pattern, minLength, maxInclusive, languageIn, rootClass → computed
  fun `a nodeKind list with sh:BlankNode does not make the parameter dynamic`()
  fun `a blank node that is no node expression at a blank-excluding parameter is a compile error`()   // [ ex:foo 1 ] → cause ShapeCompileException
  fun `hostile computed values cannot change the validator query`(provider)
      // string parameter (sh:datatype xsd:string) ; validator "ASK { FILTER (STR($value) != $forbidden) }" ; data ex:forbidden values:
      //   "x\") || true || (\"", "} ; DROP ALL ; #", "a\\u0022b" (backslash-u), "a\\\\u0022b", "line\nbreak" → no exception; result equals the
      //   result of the same validator with the value as a constant parameter whose lexical has no special characters (oracle by STR comparison)
  fun `a computed value with a backslash takes the provider-binding path`()   // unit test on SparqlQueryTemplate.bind(values, providerOnly): text unchanged at the slot; binding present
  fun `a data cycle through a component parameter expression is a failure`()  // parameter expression reads the shape via shnex:filterShape over a cycle → throws (R16)
  ```
- [ ] **Step 2: Run them and confirm they fail.**
  Run: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests 'com.geoknoesis.kastor.rdf.shacl.DynamicSparqlComponentTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the `blankExcluding` classification, the computed entries, `dynamicParameterPaths` and the `constraintExpressions`/reachability edits.**
- [ ] **Step 4: Implement `bind(values, providerOnly)`, `renderableFromData`, `resolveComponentParameters` and the dispatch.**
- [ ] **Step 5: Run them and confirm they pass** on both providers. Then run `--tests '*SparqlConstraintComponent*'` to check there is no regression for constant parameters.
- [ ] **Step 6: Mutation runs.**
  - (a) Make `renderableFromData` ignore backslashes. The `a\\u0022b` cases of the hostile test must FAIL on at least one provider. If neither fails, record this in the task report as evidence that the providers do not pre-decode, and keep the guard anyway (defence in depth).
  - (b) Treat `sh:class` as blank-excluding. `…keeps a blank-node constant` must FAIL.
  - (c) Accept a blank-node output. `…a blank node…fails` must FAIL.
  - Restore after each.
- [ ] **Step 7: Commit.** Message: `feat(shacl): Dynamic SHACL for SPARQL-based constraint component parameters`, ending with the Co-Authored-By line.

### Task 7: Documentation, changelog and final verification

**Files:**
- Modify: `docs/kastor/features/shacl-validation.md`:
  - add a new `#### Dynamic SHACL` section after `#### Node expressions` (under `### SHACL-SPARQL`). It covers:
    - D1 (always on);
    - the D2/D3 table, with the reasons;
    - D4;
    - D5;
    - D6;
    - D8;
    - D9;
    - D10 (the declaration table, the data-trust rendering rule, no cache impact);
    - the note that this is native-engine only;
  - in `#### SPARQL-based constraint components`, add a cross-reference;
  - in `### Unsupported features`, add the component parameters that stay constant.
- Modify: `CHANGELOG.md` `## [Unreleased]` → `### Added`. Add one bullet for Dynamic SHACL (§8): the parameter groups, the SPARQL-component rule and the fail-closed output rules.
- Modify: the `CompiledShapeGraph` KDoc in `ShapesCompiler.kt` (:236 area) to list parameter expressions among the expression positions.

- [ ] **Step 1: Write the docs and changelog** from D1–D10. Every example is a tested shape (Examples 31 and 34, and the MaxAge component).
- [ ] **Step 2: Grep for stale wording.**
  Run: `grep -n "Dynamic SHACL\|§8" docs/kastor/features/shacl-validation.md CHANGELOG.md`.
  Expected: only the new section, cross-reference and bullet.
- [ ] **Step 3: Final verification.**
  Run: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`.
  Expected: BUILD SUCCESSFUL, the W3C pass count unchanged from the base, and `checkLegacyAbi` green with no ABI dump change.
- [ ] **Step 4: Commit.**
  ```bash
  git add docs/kastor/features/shacl-validation.md CHANGELOG.md rdf/shacl/validation/src/main
  git commit -m "docs(shacl): document Dynamic SHACL" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```

## Decided by the user (2026-10-24)

- **Q1.** Zero outputs for a single-node parameter is a failure (D6).
- **Q2.** Always on, with no flag and no ABI change (D1).
- **Q3.** SPARQL-component parameters are included, under the D10 rule (Task 6).

## Open questions (user decision)

- **Q4 — backslash rendering for focus and value nodes.** D10 sends data-derived computed values that contain `\` to the provider-binding path, because the providers pre-decode `\u` escapes. Today `$this` and `$value` (focus and value nodes, also from the data) are text-substituted by the same `renderTerm`, so they have the same exposure. Should the same guard apply to them? It would be a small change in `evaluateSparql`/`evaluateSparqlComponent`/node-expression `select`, outside §8's scope. It could be folded into Task 6 or left as a separate fix.
