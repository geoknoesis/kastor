# SHACL 1.2 SPARQL-Related Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kastor's native SHACL engine a conforming SHACL 1.2 *SPARQLValidation* processor (§2–§5: prefixes, `sh:sparql`, SPARQL-based constraint components, result annotations) and *SPARQLNodeExpr* processor (§6: `sh:select` / `sh:sparqlExpr` node expressions at `sh:targetNode`, `sh:values`, `sh:defaultValue`).

**Architecture:** The engine already compiles `sh:sparql` into `PropertyConstraint.Sparql` (in `ShapesCompiler`) and runs queries through `SparqlConstraintEvaluator.Session` on a SPARQL provider (Jena or RDF4J), with pre-binding done by text substitution in `SparqlQueryTemplate`. This plan generalizes that template so it takes any set of pre-bound names (parameters, `value`). It then adds two new compiled forms beside `PropertyConstraint.Sparql`: `PropertyConstraint.SparqlComponent` and a small `NodeExpression` model. All of them evaluate through one shared solution-to-result mapper. New code goes in new files, so the 1,100-line `ShapesCompiler.kt` only gains call sites.

**Tech Stack:** Kotlin/JVM, JUnit 5 (parameterized over the `jena` and `rdf4j` providers), Gradle via the serialized runner `bash /c/tmp/kastor-gradle.sh` (never run two Gradle builds at once on this machine).

**Spec:** https://www.w3.org/TR/shacl12-sparql/ (W3C Working Draft). Also cited: SHACL 1.2 Core §3.1.3.1 (node targets), §3.3 and §6.9.2 (value nodes of property shapes, `sh:values` / `sh:defaultValue`). The W3C test suite is checked out at `rdf/shacl/validation/test-data/w3c-shacl12/tests/sparql/`.

## Global Constraints

- Module: `:rdf:shacl-validation`. Sources: `rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/`. Tests: `rdf/shacl/validation/src/test/kotlin/com/geoknoesis/kastor/rdf/shacl/`.
- Unit tests: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*<Name>*'`. W3C suite: `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:w3cConformanceTest`. Exit code 75 means the build lock is busy, so retry later.
- Work on branch `feat/shacl12-sparql`, not `main`.
- No new dependencies. Do not edit `rdf/core` vocabulary. Missing `sh:` terms go into a new internal `ShaclSparqlTerms` object (Task 1), following the existing `Iri(SHACL.namespace + "…")` pattern in `ShapesCompiler`.
- Ill-formed shapes graphs throw `ShapeCompileException` (or its subclass `SparqlPreBindingRestrictionException` for the Appendix A restrictions). SPARQL execution errors and `?failure` solutions throw `ShaclValidationException`.
- Reserved variable names (spec §4.2.1): `this`, `path`, `PATH`, `value`, `message`, `failure`.
- Kept on purpose, pending the user's decision (see hand-off):
  - Blanket rejection of `VALUES`: the approved W3C test `unsupported-sparql-002` expects a failure for `VALUES ?any { true }`, although spec Appendix A only forbids `VALUES` that mentions a pre-bound variable.
  - `$currentShape` / `$shapesGraph` pre-binding (removed in SHACL 1.2, kept here as a Kastor extension).
- `SERVICE` stays rejected. Appendix A: "Implementations that do not permit SERVICE MUST report a failure".
- Every public API change must pass `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:checkLegacyAbi`. Regenerate the dump with `:rdf:shacl-validation:updateLegacyAbi` in the task that changes it.

## Out of Scope (each needs its own plan)

- **§7 custom SPARQL functions (`sh:ListParameterExpressionFunction` called from SPARQL).** The engine has no SPARQL processor of its own, so these need a new provider SPI that registers executable functions per query in Jena (`FunctionRegistry` in the query `Context`) and RDF4J. Section 7.1 also needs SHACL 1.2 Node Expressions (`shnex:`). The three `sparql/functions/*` W3C cases stay in `W3cKnownDeviations` as `SHACL_FUNCTION`.
- `shnex:` node expressions, `sh:expression`, and non-constant `sh:deactivated`. These stay reported as `NODE_EXPRESSION`.
- Detecting `$PATH` used outside predicate position (an ill-formed query, spec §3.2). A misuse surfaces as a SPARQL parse failure at run time.
- Checking parameter values against `sh:Parameter` constraints. The spec makes this optional ("Some implementations MAY…").

## Review Focus

1. **Blank-node focus nodes and parameter values in component queries.** These cannot be substituted textually, so they become provider initial bindings. The ASK and SELECT validators must give the same results on Jena and RDF4J. Test in Task 5: `ASK validator flags a blank-node value node on both providers`.
2. **A shape with two values for one component parameter** (`ex:lang "de", "en"`) yields one constraint per value combination, each validated separately. Test in Task 4: `two parameter values give two constraints`.
3. **A component whose only validator does not fit the shape kind** (only `sh:nodeValidator`, used on a property shape) is ignored, with no error and no results. Test in Task 4: `validator that does not fit the shape kind is ignored`.
4. **`sh:values` with unbound or duplicate solutions, and `sh:values` on a non-predicate path.** Unbound rows add nothing, duplicates collapse, and a non-predicate path is ill-formed. Tests in Tasks 6 and 7.
5. **A select expression that does not project exactly one variable** (`SELECT *`, two variables) is ill-formed at compile time, not a run-time crash. Test in Task 6: `select expression must project exactly one variable`.

---

### Task 1: SHACL 1.2 prefix handling

**Files:**
- Create: `…/shacl/native/ShaclSparqlTerms.kt`
- Create: `…/shacl/native/SparqlPrefixes.kt`
- Modify: `…/shacl/native/ShapesCompiler.kt`: delete `collectPrefixDeclarations` (around line 984) and make `compileSparqlConstraint` call `SparqlPrefixes`.
- Test: `…/shacl/native/SparqlPrefixesTest.kt`

**Interfaces:**
- Produces:
  - `internal object ShaclSparqlTerms` with `Iri` vals: `validator`, `nodeValidator`, `propertyValidator`, `SPARQLAskValidator`, `SPARQLSelectValidator`, `optional`, `resultAnnotation`, `annotationProperty`, `annotationVarName`, `annotationValue`, `values`, `defaultValue`, `sparqlExpr`, `ShapesGraph`, `DataGraph`, `RulesGraph`. Each is `Iri(SHACL.namespace + "<localName>")`.
  - `internal object SparqlPrefixes { fun collect(node: RdfResource, index: ShapeGraphIndex): Map<String, String>; fun prologue(prefixes: Map<String, String>): String }`. `prologue` renders `PREFIX p: <ns>\n` per entry, and throws `ShapeCompileException` when `SparqlQueryTemplate.renderIri(ns)` is null.

- [ ] **Step 1: Write the failing tests** in `SparqlPrefixesTest`. Build each index with `ShapeGraphIndex(Rdf.parse(ttl, RdfFormat.TURTLE).getTriples())` and call `SparqlPrefixes.collect(ex("C"), index)`:
  - `prefixes reach declarations through owl versionIRI and imports`: `ex:C sh:prefixes ex:v1 . ex:G owl:versionIRI ex:v1 ; owl:imports ex:v2 ; sh:declare [ sh:prefix "a" ; sh:namespace "http://a/"^^xsd:anyURI ] . ex:H owl:versionIRI ex:v2 ; sh:declare [ sh:prefix "b" ; sh:namespace "http://b/" ] .` gives `mapOf("a" to "http://a/", "b" to "http://b/")`.
  - `graph-level declarations apply only without sh prefixes`: an `ex:SG a sh:ShapesGraph ; sh:declare [prefix "z"]` is ignored when `ex:C` has `sh:prefixes`. Without `sh:prefixes`, declarations at instances of `owl:Ontology`, `sh:DataGraph`, `sh:ShapesGraph`, `sh:RulesGraph`, and of `ex:Mine rdfs:subClassOf sh:ShapesGraph`, are all collected.
  - `conflicting namespaces for one prefix are ill-formed`: two declarations of `"a"` with different namespaces throw `ShapeCompileException`. This applies to both the `sh:prefixes` and the fallback route.
  - `prefix and namespace datatypes are checked`: `sh:prefix "a"^^xsd:token` and `sh:namespace 5` each throw `ShapeCompileException`. `xsd:string` and `xsd:anyURI` namespaces are accepted.
- [ ] **Step 2: Run them and confirm they fail.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*SparqlPrefixesTest*'`. Expected: compilation failure, because `SparqlPrefixes` does not exist yet.
- [ ] **Step 3: Implement `SparqlPrefixes.collect`.**
  - Start from the `sh:prefixes` values. Before reading `sh:declare`, map each holder `h` to `h` itself plus `index.subjects(OWL.versionIRI, h)` (the `^owl:versionIRI` step). Follow `owl:imports` with the same mapping, with a visited set.
  - If the node has no `sh:prefixes`, use instead every resource `r` with `index.isInstanceOf(r, X)` for X in {`OWL.Ontology`, `ShaclSparqlTerms.DataGraph`, `ShaclSparqlTerms.ShapesGraph`, `ShaclSparqlTerms.RulesGraph`}.
  - Datatype checks: `sh:prefix` must be `xsd:string`; `sh:namespace` must be `xsd:string` or `xsd:anyURI`.
  - Then replace the body of the old `collectPrefixDeclarations` call in `compileSparqlConstraint` with `SparqlPrefixes.prologue(SparqlPrefixes.collect(node, index)) + body`.
- [ ] **Step 4: Run the tests and confirm they pass.** Run the Step 2 command, then `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test --tests '*Sparql*'`. Expected: PASS. Existing SPARQL tests that relied on graph-level declarations alongside `sh:prefixes` must be updated to follow the spec; note each one you change in the commit message.
- [ ] **Step 5: Commit.** `git add` the four files, then `git commit -m "feat(shacl): SHACL 1.2 prefix declaration rules for SPARQL queries"`.

### Task 2: Pre-binding template takes its pre-bound names; SHACL 1.2 relaxations

**Files:**
- Modify: `…/shacl/native/SparqlQueryTemplate.kt`
- Modify: `…/shacl/native/ShapesCompiler.kt`: `compileSparqlConstraint`.
- Modify: `…/shacl/ShaclValidationExceptions.kt`: KDoc of `SparqlPreBindingRestrictionException`, removing "a sub-query that does not project `$this`".
- Test: `…/shacl/native/SparqlQueryTemplateTest.kt` (create), and update `…/shacl/native/SparqlPreBindingProviderTest.kt`.

**Interfaces:**
- Produces:
  - `SparqlQueryTemplate.compile(query: String, preBound: Set<String>): SparqlQueryTemplate`
  - `SparqlQueryTemplate.restrictionViolation(query: String, preBound: Set<String>): String?`
  - `SparqlQueryTemplate.substitutePath(query: String, rendered: (() -> String)?): String`, where null means "leave PATH unbound" (node shapes).
  - `SparqlQueryTemplate.askAsSelect(query: String): String`
  - `SparqlQueryTemplate.projectedVariables(query: String): List<String>?`
  - `SparqlQueryTemplate.localName(iri: Iri): String`
  - `SparqlQueryTemplate.isVarName(name: String): Boolean`
  - `SparqlQueryTemplate.SPARQL_CONSTRAINT_PRE_BOUND: Set<String> = setOf("this", "currentShape", "shapesGraph")`, replacing `PRE_BOUND_NAMES`.

- [ ] **Step 1: Write the failing tests** in `SparqlQueryTemplateTest`:
  - `parameter names are pre-bound like this`: `compile("SELECT ?x WHERE { ?x <p> ${'$'}lang }", setOf("this","lang")).bind(mapOf("lang" to string("de"))).first` contains `"de"^^<http://www.w3.org/2001/XMLSchema#string>` and no `$lang`.
  - `AS rebinding any pre-bound name is rejected`: `restrictionViolation("ASK { BIND (true AS ?value) }", setOf("this","value"))` is non-null, and the same query with `setOf("this")` gives null.
  - `sub-select not projecting this is allowed`: `restrictionViolation("SELECT ${'$'}this WHERE { { SELECT ?y WHERE { ${'$'}this <p> ?y } } }", setOf("this"))` is null.
  - `MINUS SERVICE and VALUES stay rejected`: each is non-null.
  - `PATH with either sigil is substituted and stays unbound without a path`: `substitutePath("?a ?PATH ?b . ?a ${'$'}PATH ?c") { "<p>" }` gives `"?a <p> ?b . ?a <p> ?c"`, and `substitutePath(q, null) == q`.
  - `ask becomes a select`: `askAsSelect("PREFIX ex: <e:>\nASK { FILTER (true) }")` gives `"PREFIX ex: <e:>\nSELECT (1 AS ?__kastor_ask) { FILTER (true) }"`. `askAsSelect("ASK WHERE {}")` gives `"SELECT (1 AS ?__kastor_ask) WHERE {}"`. A `# ASK` inside a comment is untouched.
  - `projected variables of the outermost select`: `"SELECT ?fullName WHERE {…}"` gives `[fullName]`. `"SELECT DISTINCT ?a ?b {…}"` gives `[a, b]`. `"SELECT (STRLEN(STR(${'$'}this)) AS ?result) WHERE {}"` gives `[result]`. `"SELECT (COUNT(?label) AS ?result) WHERE { { SELECT ?z {} } }"` gives `[result]`. `"SELECT * {}"` gives null.
  - `local names and var names`: `localName(Iri("http://e/ns#lang")) == "lang"`. `localName(Iri("urn:x:test1")) == "test1"`. `isVarName("arg0")` is true, and `isVarName("0a-")` is false.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*SparqlQueryTemplateTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement.**
  - Thread `preBound` through `compile`, `matchBoundOfPreBound` and `restrictionViolation`, and delete the sub-select projection check.
  - `isPathToken` matches the variable named `PATH` with either sigil.
  - `askAsSelect` replaces the first significant `WORD` token equal to `ASK` (case-insensitive) with `SELECT (1 AS ?__kastor_ask)`.
  - `projectedVariables`: start at the first `SELECT` word and skip `DISTINCT` / `REDUCED`. Read tokens until `WHERE`, `FROM` or `{` at parenthesis depth 0. A `VARIABLE` at depth 0 is projected, and so is the `VARIABLE` that follows `AS`. A `*` returns null.
  - `localName`: the longest suffix matching NCName that is not preceded by the IRI's first colon.
  - `compileSparqlConstraint`: compile with `SPARQL_CONSTRAINT_PRE_BOUND`, and pass `path?.let { { renderSparqlPath(it) } }` to `substitutePath`, which deletes the "uses `$PATH` but is not attached to a property shape" error.
  - Sub-selects that use a pre-bound name they do not project now compile. A blank-node value is then rejected by the provider contract (`SparqlQueryable.withSelectRows` KDoc), and `Session` turns that into `ShaclValidationException`.
- [ ] **Step 4: Update `SparqlPreBindingProviderTest`.** The `VALUES MINUS and AS rebinding…` test keeps its three bodies. Add `sub-select without this works for IRI focus nodes and fails for blank-node focus nodes` (both providers), asserting a `ShaclValidationException` for the blank-node case.
- [ ] **Step 5: Run the tests and confirm they pass.** Run `…:test --tests '*Sparql*'` and `…:w3cConformanceTest`. Expected: PASS, with no change in the W3C deviations.
- [ ] **Step 6: Commit.** `git commit -m "feat(shacl): configurable SPARQL pre-binding, SHACL 1.2 sub-select and PATH rules"`.

### Task 3: Shared solution mapping and result annotations (§3.3.2, §5)

**Files:**
- Create: `…/shacl/native/SparqlResultAnnotations.kt`
- Modify: `…/shacl/ValidationResults.kt`: `ValidationViolation`.
- Modify: `…/shacl/ValidationReportRdf.kt`: `addValidationResult`.
- Modify: `…/shacl/native/ShapesCompiler.kt`: `PropertyConstraint.Sparql` and `compileSparqlConstraint`.
- Modify: `…/shacl/providers/NativeSparqlConstraints.kt`
- Modify: `rdf/shacl/validation/api/*.api` (regenerated)
- Test: `…/shacl/SparqlResultAnnotationTest.kt` (create), plus a case in `…/shacl/ValidationReportRdfTest.kt`.

**Interfaces:**
- Consumes: `SparqlQueryTemplate.localName` (Task 2).
- Produces:
  - `ValidationViolation.sourceConstraintComponent: Iri? = null`. When non-null, the report writes it instead of `constraint.constraintType.toSourceConstraintComponentIri()`.
  - `ValidationViolation.resultAnnotations: Map<Iri, List<RdfTerm>> = emptyMap()`, written as `row <property> <value>` per value.
  - `internal data class SparqlResultAnnotation(val property: Iri, val variable: String, val defaults: List<RdfTerm>)`
  - `internal fun compileResultAnnotations(node: RdfResource, index: ShapeGraphIndex): List<SparqlResultAnnotation>`
  - `PropertyConstraint.Sparql.annotations: List<SparqlResultAnnotation>`
  - In `NativeSparqlConstraints.kt`, the shared mapper:

    ```kotlin
    internal class SparqlResultSource(
      val constraintNode: RdfResource?, val component: Iri?, val messages: List<Literal>,
      val severity: ViolationSeverity?, val severityCustomIri: Iri?, val annotations: List<SparqlResultAnnotation>,
    )
    internal fun solutionViolation(
      focus: RdfTerm, row: (String) -> RdfTerm?, preBound: Map<String, RdfTerm>,
      tpl: ResultTemplate, source: SparqlResultSource, hasPath: Boolean,
    ): ValidationViolation
    ```

- [ ] **Step 1: Write the failing tests.**
  - In `SparqlResultAnnotationTest` (parameterized `jena`/`rdf4j`, using the `validate(provider, data, shapes)` helper style of `SparqlPreBindingProviderTest`):
    - `annotation copies a binding by var name`: spec Example 9 with `BIND ("t" AS ?time)` instead of `NOW()`. The single violation has `resultAnnotations == mapOf(ex("time") to listOf(string("t")))` and `resultMessages.single().lexical == "The message."`.
    - `annotation var name defaults to the property local name`: the same, without `sh:annotationVarName`.
    - `unbound annotation variable falls back to annotationValue`: `sh:annotationValue 42` gives `listOf(42 literal)`.
    - `annotation property must be a single IRI`: a missing or literal `sh:annotationProperty` throws `ShapeCompileException`.
  - In `ValidationReportRdfTest`: `custom source component and annotations are exported` asserts the triples `sh:sourceConstraintComponent ex:C` and `ex:time "t"` on the result row.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*SparqlResultAnnotationTest*' --tests '*ValidationReportRdfTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement.**
  - Add the two `ValidationViolation` fields at the end of the constructor.
  - Move the row-to-violation code out of `evaluateSparql` into `solutionViolation` (`evaluateSparql` passes `row::get`), unchanged:
    - `?value`, falling back to the focus node for node shapes;
    - `?path` when it is an IRI, otherwise the template path;
    - `?message`, then source messages, then shape messages, with `{?v}`/`{$v}` substitution from the row and then `preBound`;
    - severity;
    - `sourceConstraint`.
  - Then add `sourceConstraintComponent = source.component` and the annotations: binding of `variable`, otherwise `defaults`, omitting empty entries.
  - `compileResultAnnotations` reads `ShaclSparqlTerms.resultAnnotation` values, `sh:annotationProperty` (exactly one IRI), `sh:annotationVarName` (at most one `xsd:string`, defaulting to `localName(property)`) and `sh:annotationValue`.
- [ ] **Step 4: Regenerate and check the ABI dump.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:updateLegacyAbi :rdf:shacl-validation:checkLegacyAbi`. Expected: BUILD SUCCESSFUL, and `git diff rdf/shacl/validation/api` shows only the two new `ValidationViolation` members and their constructor and `copy` overloads.
- [ ] **Step 5: Run the tests and confirm they pass.** Run the Step 2 command and `…:test --tests '*Sparql*'`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "feat(shacl): SHACL-SPARQL result annotations and custom source component in reports"`.

### Task 4: Compile SPARQL-based constraint components (§4.2)

**Files:**
- Create: `…/shacl/native/SparqlConstraintComponents.kt`
- Modify: `…/shacl/native/ShaclPath.kt`: add a lazy catalog val to `ShapeGraphIndex`.
- Modify: `…/shacl/native/ShapesCompiler.kt`:
  - add `PropertyConstraint.SparqlComponent`;
  - call the catalog from `compileConstraints` for both flavours;
  - in `detectUnsupportedFeatures`, remove the `components` collection and the `SPARQL_CONSTRAINT_COMPONENT` reporting;
  - make `resourceOrdering` internal.
- Test: `…/shacl/native/SparqlConstraintComponentCompileTest.kt`

**Interfaces:**
- Consumes: `SparqlPrefixes` (Task 1); `SparqlQueryTemplate.compile/restrictionViolation/substitutePath/askAsSelect/localName/isVarName` (Task 2); `compileResultAnnotations` (Task 3).
- Produces:

  ```kotlin
  data class SparqlComponent(          // in PropertyConstraint
    val component: Iri,
    val validator: RdfResource,
    val ask: Boolean,                  // true: sh:validator ASK (pre-binds this, value, params)
    val template: SparqlQueryTemplate,
    val parameters: Map<String, RdfTerm>,   // parameter name -> one value of this combination
    val messages: List<Literal>,       // validator sh:message, else component sh:message
    val annotations: List<SparqlResultAnnotation>,
  ) : PropertyConstraint()
  internal class SparqlComponentCatalog {
    fun constraintsFor(shape: RdfResource, propertyShape: Boolean, path: ShaclPath?): List<PropertyConstraint.SparqlComponent>
    companion object { fun build(index: ShapeGraphIndex): SparqlComponentCatalog }
  }
  // member of ShapeGraphIndex:
  val sparqlComponents: SparqlComponentCatalog by lazy { SparqlComponentCatalog.build(this) }
  ```

- [ ] **Step 1: Write the failing tests** in `SparqlConstraintComponentCompileTest`. Compile with `ShapesCompiler.compile(Rdf.parse(ttl).getTriples(), ValidationConfig.default())` and inspect `referencedNodeShapes[..].nodeConstraints` / `referencedPropertyShapes[..].constraints`:
  - `component declared through a subclass is recognized`: the `validator-001` shapes (`ex:ConstraintComponent rdfs:subClassOf sh:ConstraintComponent`, `ex:SPARQLAskValidator rdfs:subClassOf sh:SPARQLAskValidator`, IRI parameter declarations) yield one `SparqlComponent` with `ask == true` and `parameters == mapOf("test1" to string("Hello "), "test2" to string("World"))`.
  - `validator selection order`: a component with `sh:nodeValidator`, `sh:propertyValidator` and `sh:validator` gives `ask == false` and the node validator on a node shape, the property validator on a property shape, and the ASK validator when only `sh:validator` is present.
  - `validator that does not fit the shape kind is ignored` (Review Focus 3): only `sh:nodeValidator`, used by a property shape, gives no `SparqlComponent` and no exception.
  - `missing mandatory parameter declares no constraint`: a shape with only the optional parameter gives none.
  - `two parameter values give two constraints` (Review Focus 2): `ex:lang "de", "en"` gives two `SparqlComponent`s, with `parameters["lang"]` equal to `"de"` and `"en"`.
  - `ill-formed declarations`: each of these throws `ShapeCompileException`:
    - parameter name `value`;
    - two parameters with local name `x`;
    - all parameters optional;
    - `sh:optional "yes"`;
    - a `sh:nodeValidator` without `sh:select`;
    - a `sh:validator` without `sh:ask`;
    - a SELECT validator that does not project `this`.
  - `ASK rebinding value is a pre-binding restriction`: the `unsupported-sparql-006` validator throws `SparqlPreBindingRestrictionException`.
  - `core components in the sh namespace are not redefined`: spec Example 4 (`sh:PatternConstraintComponent` with an ASK validator) adds no `SparqlComponent`; `sh:pattern` still compiles to `PropertyConstraint.Pattern`.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*SparqlConstraintComponentCompileTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement `SparqlComponentCatalog.build` and `constraintsFor`.**
  - **Components:** IRIs `c` outside `SHACL.namespace` with `index.isInstanceOf(c, SHACL.ConstraintComponent)`.
  - **Parameters:** `sh:parameter` values, each with exactly one IRI `sh:path` and at most one `xsd:boolean` `sh:optional`. The name is `localName(path)`. Check `isVarName`, the reserved list, uniqueness, and at least one mandatory parameter.
  - **Use:** a shape uses `c` when it has values for every mandatory parameter's path. The constraints are the cartesian product of the value lists of the parameters present, with absent optional parameters left unbound.
  - **Validator:** the first fitting value by `resourceOrdering`. `nodeValidator` / `propertyValidator` must be `isInstanceOf SPARQLSelectValidator` with exactly one `xsd:string` `sh:select` that projects `this` (check with `projectedVariables`). `validator` must be `isInstanceOf SPARQLAskValidator` with exactly one `xsd:string` `sh:ask`.
  - **Query text:** `SparqlPrefixes.prologue(collect(validator))` followed by the text. For SELECT, use `substitutePath(text, path?.let { { renderSparqlPath(it) } })`. For ASK, use `askAsSelect`.
  - **Pre-bound names:** `{this} + parameter names`, plus `value` for ASK. Run `restrictionViolation` with them.
  - **Messages:** the validator's `sh:message`, otherwise the component's.
  - Only components used by at least one shape-reachable node are checked for well-formedness.
- [ ] **Step 4: Run the tests and confirm they pass.** Run the Step 2 command and `…:test --tests '*UnsupportedFeatureDetectionTest*'`. Expected: PASS, after updating detection tests that expected `SPARQL_CONSTRAINT_COMPONENT` so they now expect a compiled constraint.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): compile SHACL-SPARQL constraint components"`.

### Task 5: Evaluate SPARQL-based constraint components (§4.3)

**Files:**
- Create: `…/shacl/providers/NativeSparqlComponents.kt`
- Modify: `…/shacl/providers/NativePropertyConstraints.kt`: the `evaluateConstraint` dispatch.
- Modify: `…/shacl/providers/NativeShaclValidatorProvider.kt`: `supportsCustomConstraints = true`.
- Modify: `…/shacl/conformance/Shacl12NativeConformanceTest.kt`: remove the four `SPARQL_COMPONENTS` entries and the now-unused `SPARQL_COMPONENTS` val.
- Test: `…/shacl/SparqlConstraintComponentTest.kt` (create)

**Interfaces:**
- Consumes: `PropertyConstraint.SparqlComponent` (Task 4); `solutionViolation`, `SparqlResultSource` (Task 3); `NativeValidationContext.select`.
- Produces: `internal fun evaluateSparqlComponent(focus: RdfTerm, tpl: ResultTemplate, values: List<RdfTerm>, c: PropertyConstraint.SparqlComponent, ctx: NativeValidationContext, hasPath: Boolean, firstOnly: Boolean): List<ValidationViolation>`

- [ ] **Step 1: Write the failing tests** in `SparqlConstraintComponentTest`, parameterized over `jena`/`rdf4j`:
  - `select property validator reports spec example 7`: spec Examples 6 and 7 with data `ex:Bad a ex:Country ; ex:germanLabel "x"@en`. Expect one violation with:
    - `focusNode == ex("Bad")`, `value == lang("x","en")`, `path == listOf(ex("germanLabel"))`;
    - `sourceConstraintComponent == ex("LanguageConstraintComponentUsingSELECT")`;
    - `resultMessages.single().lexical == "Values are literals with language \"de\""`.
  - `ask validator on a node shape uses the focus node as value`: the `validator-001` data gives a single violation on `"Hallo Welt"`.
  - `ASK validator flags a blank-node value node on both providers` (Review Focus 1): a property shape over `ex:p` with an ASK validator `FILTER (!isBlank($value))` and data `ex:a ex:p [] , ex:b`. Exactly one violation, whose value is the blank node.
  - `optional parameter left unbound`: the `optional-001` shapes reproduce the four expected results (`focusNode`/`value`/`sourceShape` triples).
  - `failure binding in a select validator fails validation`: `SELECT $this (true AS ?failure) {}` throws `ShaclValidationException`.
  - `conformance check stops at the first result`: use the component inside `sh:not` and assert that `conforms` is false and no exception is thrown.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*SparqlConstraintComponentTest*'`. Expected: FAIL, because no results are produced (the constraint is ignored by dispatch).
- [ ] **Step 3: Implement `evaluateSparqlComponent`.**
  - The pre-bound values are `this` plus `c.parameters`.
  - **ASK:** for each value node `v`, bind `value`, run `ctx.select(query, bindings) { it.any() }`, and on `false` emit `solutionViolation` with `row = mapOf("this" to focus, "value" to v)::get`.
  - **SELECT:** stream the rows as `evaluateSparql` does, including the `?failure` scan and the `firstOnly` early stop.
  - Use `SparqlResultSource(constraintNode = null, component = c.component, messages = c.messages, severity = null, severityCustomIri = null, annotations = c.annotations)`.
  - Dispatch with `is PropertyConstraint.SparqlComponent -> sink.failAll(evaluateSparqlComponent(focus, tpl, values, c, ctx, path != null, firstOnly = !sink.reports))`.
- [ ] **Step 4: Run the tests and confirm they pass.** Run the Step 2 command, then `…:w3cConformanceTest`. Expected: PASS. `sparql/component/*` and `unsupported-sparql-006` now pass as normal cases, and 006 fails with `SparqlPreBindingRestrictionException` as `W3cExpectedFailures` requires.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): evaluate SHACL-SPARQL constraint components"`.

### Task 6: Compile SPARQL node expressions (§6) and constant `sh:values` / `sh:defaultValue`

**Files:**
- Create: `…/shacl/native/NodeExpressions.kt`
- Modify: `…/shacl/native/ShapesCompiler.kt`:
  - add `Targets.targetNodeExpressions`, `CompiledPropertyShape.values` and `CompiledPropertyShape.defaultValue`;
  - update `compilePropertyShape` and the target compilation (around line 595);
  - in `detectUnsupportedFeatures`, report `sh:values`, `sh:defaultValue`, `sh:targetNode` and `sh:targetWhere` only when `NodeExpressions.compile` returns null.
- Test: `…/shacl/native/NodeExpressionCompileTest.kt`

**Interfaces:**
- Consumes: `SparqlPrefixes`, `SparqlQueryTemplate.compile/restrictionViolation/projectedVariables`.
- Produces:

  ```kotlin
  internal sealed class NodeExpression {
    data class Constant(val term: RdfTerm) : NodeExpression()                       // IRI, literal or triple term
    class Select(val node: BlankNode, val template: SparqlQueryTemplate, val variable: String) : NodeExpression()
  }
  internal object NodeExpressions { fun compile(term: RdfTerm, index: ShapeGraphIndex): NodeExpression? }  // null = not a supported expression
  // Targets.targetNodeExpressions: List<NodeExpression.Select>
  // CompiledPropertyShape.values: NodeExpression? ; CompiledPropertyShape.defaultValue: NodeExpression?
  ```

- [ ] **Step 1: Write the failing tests** in `NodeExpressionCompileTest`:
  - `sparqlExpr compiles to a select of result`: `[ sh:sparqlExpr "STRLEN(STR($this))" ]` gives `Select` with `variable == "result"`. With `sh:prefixes`, the declared prologue comes first.
  - `select expression must project exactly one variable` (Review Focus 5): `SELECT * {}` and `SELECT ?a ?b {}` each throw `ShapeCompileException`.
  - `select expression syntax rules`: an IRI subject with `sh:select`, two `sh:select` values, or two `sh:prefixes` values each throw `ShapeCompileException`.
  - `this is pre-bound and may not be rebound`: `SELECT ?x { BIND (1 AS $this) }` throws `SparqlPreBindingRestrictionException`.
  - `shnex expressions stay unsupported`: `[ shnex:count … ]` gives `compile(...) == null`, and `ShapesCompiler.compile` with default config throws `UnsupportedShaclFeatureException` with category `NODE_EXPRESSION`.
  - `sh:values only on predicate paths` (Review Focus 4): `sh:path [ sh:inversePath ex:p ] ; sh:values ex:x` throws `ShapeCompileException`. Two `sh:values` values also throw.
  - `constant values and default value compile`: `sh:values ex:x ; sh:defaultValue 1` gives `values == Constant(ex:x)` and `defaultValue == Constant(1)`.
  - `targetNode select is a target expression`: the `targetNode-select-001` shapes give one `targetNodeExpressions` entry and no blank node in `targetNodes`.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*NodeExpressionCompileTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement `NodeExpressions.compile`.**
  - An `Iri`, `Literal` or `TripleTerm` gives `Constant`.
  - A `BlankNode` with `sh:select` must have exactly one `xsd:string`; check `projectedVariables` has size 1.
  - A `BlankNode` with `ShaclSparqlTerms.sparqlExpr` must have exactly one `xsd:string`. Its query is `prologue + "SELECT (" + expr + " AS ?result) WHERE {}"`.
  - Both kinds take at most one `sh:prefixes`, and are compiled with `preBound = setOf("this")` after `restrictionViolation`.
  - Anything else gives null.
  - `sh:values` / `sh:defaultValue`: at most one value each, and only when `path is ShaclPath.Predicate`.
- [ ] **Step 4: Run the tests and confirm they pass.** Run the Step 2 command and `…:test --tests '*UnsupportedFeatureDetectionTest*'`. Expected: PASS, after updating cases that expected `SPARQL_NODE_EXPRESSION` for now-supported forms.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): compile SPARQL node expressions and constant sh:values"`.

### Task 7: Evaluate node expressions at targets and value nodes

**Files:**
- Create: `…/shacl/providers/NativeNodeExpressions.kt`
- Modify: `…/shacl/providers/NativeTargetPlanning.kt`: `computeFocusNodes`.
- Modify: `…/shacl/providers/NativeShapeEvaluation.kt`: the value computation in `validatePropertyShape` (line 201).
- Modify: `…/shacl/conformance/Shacl12NativeConformanceTest.kt`: remove the three `SPARQL_EXPRESSIONS` entries and the val.
- Test: `…/shacl/NodeExpressionEvaluationTest.kt` (create)

**Interfaces:**
- Consumes: `NodeExpression` (Task 6), `NativeValidationContext.select`.
- Produces: `internal fun evalNodeExpression(expr: NodeExpression, ctx: NativeValidationContext, focus: RdfTerm?): List<RdfTerm>`. It returns distinct outputs in solution order, skips rows where the variable is unbound, and pre-binds `this` only when `focus != null`.

- [ ] **Step 1: Write the failing tests** in `NodeExpressionEvaluationTest`, parameterized over `jena`/`rdf4j`:
  - `property values come from a select expression`: the `property-select-001` case conforms. Changing `sh:hasValue` to `"John Doe"` gives one `HasValue` violation.
  - `values are unioned with path values`: data `ex:a ex:p 1`, shape `sh:path ex:p ; sh:values 2 ; sh:minCount 2` conforms.
  - `default value applies only when there are no values`: `sh:defaultValue "d" ; sh:hasValue "d"` conforms for `ex:a` without `ex:p`. For `ex:b ex:p "x"` it gives a violation.
  - `unbound and duplicate solutions` (Review Focus 4): `SELECT ?v { { BIND ("s" AS ?v) } UNION { BIND ("s" AS ?v) } UNION { BIND (1 AS ?other) } }` gives exactly one value node `"s"`, so `sh:maxCount 1 ; sh:minCount 1` conforms. Use `UNION`, not `VALUES`, which stays rejected (Global Constraints).
  - `sparqlExpr value`: spec Example 12 with focus `<http://example.com/ns#Test>` and `sh:hasValue 26` conforms.
  - `targetNode select pre-binds the shape and targets only matching nodes`: the `targetNode-select-001` data gives one violation, for `ex:Benjamin`. A second shape with `sh:targetNode [ sh:select "SELECT ?s { BIND ($this AS ?s) }" ]` targets the shape node itself (SHACL 1.2 Core §3.1.3.1: `evalExpr(expr, data graph, s, {})`).
  - `a failing expression fails validation`: `sh:sparqlExpr "1 +"` throws `ShaclValidationException`.
- [ ] **Step 2: Run them and confirm they fail.** Run `…:test --tests '*NodeExpressionEvaluationTest*'`. Expected: FAIL, because no values or targets are produced.
- [ ] **Step 3: Implement.**
  - In `computeFocusNodes`, add `shape.targets.targetNodeExpressions.forEach { out.addAll(evalNodeExpression(it, ctx, shape.shapeNode)) }`.
  - In `validatePropertyShape`, compute values as path values, plus `ps.values` outputs (deduplicated with `distinctShaclTerms`), and, if that is still empty, `ps.defaultValue` outputs. A `Constant` evaluates to `listOf(term)` without a query.
- [ ] **Step 4: Run the tests and confirm they pass.** Run the Step 2 command, then `…:w3cConformanceTest` and the full module `…:test`. Expected: PASS. Of the SPARQL entries, `W3cKnownDeviations` now lists only the three `sparql/functions/*` cases, plus `node-expr/constraints/expression-001`.
- [ ] **Step 5: Commit.** `git commit -m "feat(shacl): evaluate SPARQL node expressions at targets and value nodes"`.

### Task 8: Documentation and final verification

**Files:**
- Modify: `docs/kastor/features/shacl-validation.md` and `docs/kastor/guides/how-to-validate-shacl.md`: the SHACL-SPARQL support sections.
- Modify: `…/shacl/ValidationConfig.kt`: the KDoc at line 88 about unsupported features.
- Modify: `…/shacl/native/SparqlConstraintEvaluator.kt`: the class KDoc mentioning `$shapesGraph`.

- [ ] **Step 1: Update the docs.**
  - **Supported:** prefixes (incl. `owl:versionIRI`), `sh:sparql` with severity and annotations, constraint components (ASK and SELECT validators), `sh:select` / `sh:sparqlExpr` at `sh:targetNode` / `sh:values` / `sh:defaultValue`.
  - **Not supported:** custom SPARQL functions (§7), `shnex:` expressions.
  - **Kastor-specific:** `$currentShape` / `$shapesGraph` pre-binding, blanket `VALUES` rejection, `SERVICE` rejection.
- [ ] **Step 2: Run the full verification.** Run `bash /c/tmp/kastor-gradle.sh :rdf:shacl-validation:test :rdf:shacl-validation:w3cConformanceTest :rdf:shacl-validation:checkLegacyAbi`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit.** `git commit -m "docs(shacl): document SHACL 1.2 SPARQL support"`.
