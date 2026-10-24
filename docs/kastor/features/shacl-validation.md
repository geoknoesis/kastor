# SHACL Validation

Kastor supports SHACL validation with full **SHACL 1.2** support, including Core features and SPARQL Extensions. Validation is available in two complementary ways:

- **Kastor Gen ValidationContext** for domain materialization and `RdfHandle` validation.
- **Repository-level SHACL validation** via the `rdf/shacl/validation` module.

For the **provider model** (native Kastor engine vs optional adapters to Jena, RDF4J, and others), module layout, and implementation roadmap, see [SHACL validation architecture](../design/shacl-validation-architecture.md). For **performance benchmarking** (JMH harness, ERA-SHACL-Benchmark CLI, baselines), see [SHACL native engine: cross-implementation performance benchmarks](../design/shacl-native-engine-benchmark.md).

## Kastor Gen ValidationContext

Validation is explicit and optional. You decide when validation is enforced by passing a `ValidationContext` during materialization.

### Add a Validation Adapter

Pick the adapter that matches your backend:

```kotlin
dependencies {
    runtimeOnly(project(":kastor-gen:validation-jena"))
    // or
    runtimeOnly(project(":kastor-gen:validation-rdf4j"))
}
```

### Validate During Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asValidatedType(validation)
```

### Validate After Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asType(validation)
person.asRdf().validateOrThrow()
```

## Repository-Level SHACL Validation

Module **`rdf/shacl/validation`**: API reference, **`providerId`** table, **`maxCombinedGraphTriples` / `rdf4jUntrustedInputLimits`**, and cross-engine smoke tests live in the [module README](../../../rdf/shacl/validation/README.md).

Use the `rdf/shacl/validation` module when you want to validate graphs directly (without materialization):

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

// Create shapes using the Kotlin SHACL DSL (recommended; add dependency `com.geoknoesis.kastor:rdf-shacl-dsl`)
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

// Or create shapes manually
val shapesGraph = Rdf.graph {
    // ... manual RDF triples
}

// Validate
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { println(it.message) }
}
```

### Conformance semantics

- `report.isValid` mirrors SHACL `sh:conforms`. By default it is `false` if **any** validation result of severity `sh:Violation`, `sh:Warning`, `sh:Info` or a custom severity exists; SHACL 1.2 `sh:Debug` / `sh:Trace` results do not affect conformance.
- `ValidationConfig.conformanceDisallows: Set<Iri>?` is SHACL 1.2 `sh:conformanceDisallows`. It is the set of result severities (`SHACL.Violation`, `SHACL.Warning`, `SHACL.Info`, `SHACL.Debug`, `SHACL.Trace` or a custom severity IRI) whose results make `isValid` false. `null` (the default) applies the SHACL default above. Results of other severities are still reported; they just do not block conformance. For example, `ValidationConfig(conformanceDisallows = setOf(SHACL.Violation))` lets warnings through. Results for undefined recursion and undecidable `sh:targetWhere` carry the source shape's declared severity, so under that setting they still block for shapes of severity `sh:Violation` (the default). The native engine (`kastor`) honours this setting; the other providers do not read it.
- `report.hasViolations` is the severity-filtered check (Violation/Error only). Use it when warnings should not fail a build.
- Property shapes default to `sh:Violation`; they do not inherit a node shape's severity.
- Deactivated shapes and shapes without constraints accept every node. Literal value nodes can conform to nested shapes (`sh:node`, `sh:and`/`sh:or`/`sh:xone`/`sh:not`, qualified value shapes).
- Repeatable parameters (`sh:pattern`, `sh:hasValue`, bounds, …) produce one constraint per value. Repeating a single-valued parameter (`sh:minCount`, `sh:flags`, `sh:in`, …) is a shape compilation error. `sh:pattern` is compiled once, at shape compile time, and supports the flags `i`, `m`, `s`, `x` and `q`.
- `sh:pattern` follows XPath `fn:matches` (XML Schema regular expressions), not `java.util.regex` defaults. `$` matches only at the end of the value (with `m`: before a line feed), never before a final newline. With `m`, `^` matches at the start and after a line feed only. `.` matches everything except line feed and carriage return unless `s` is set. `\d` is any Unicode decimal digit and `\s` is exactly space, tab, line feed and carriage return.
- `\w` is `[_\p{L}\p{M}\p{N}\p{S}]`: the XML Schema class (every character except punctuation, separators and control/unassigned characters, so `^\w+$` accepts `José` and symbols such as `+` are word characters) **plus the underscore**. XML Schema excludes `_`; it is accepted because shapes are usually written against engines that evaluate patterns with `java.util.regex` (Jena, RDF4J, TopBraid), where `\w` matches `_`. `\W` is the exact complement. The [SHACL DSL guide](../api/shacl-dsl-guide.md#pattern-constraints) lists the remaining differences from Java regular expressions.
- The pattern syntax follows one rule. XML Schema syntax is translated: character-class subtraction (`[a-z-[aeiou]]`, which must end its class and may nest), `\i` / `\c` (the XML `NameStartChar` / `NameChar` productions) and their complements `\I` / `\C`, and block escapes `\p{IsBasicLatin}` (the Unicode block name without spaces). `java.util.regex` syntax that means the same after translation is passed through: lazy and possessive quantifiers, back-references, non-capturing and named groups, lookarounds, `\Q…\E`. Three common idioms of other dialects are translated with their intended meaning: a **leading** inline flag group of `i`, `m`, `s`, `x` (`(?i)^abc$` is `^abc$` with `sh:flags "i"`; the group's flags are merged with `sh:flags`), a script name in `\p{IsX}` when `X` is not a block name (`\p{IsLatin}`), and the twelve POSIX bracket expressions inside a class (`[[:alpha:]_]`, US-ASCII). Everything else that would mean something else is **rejected** when the shapes are compiled (`ShapeCompileException`): class intersection `&&`, an unescaped `[` inside a class (nested classes and unions), inline flag groups that are not at the start, are scoped or negated, or have other flags (`a(?i)b`, `(?i:…)`, `(?-i)`, `(?u)`), and `\p{IsX}` where `X` is neither a block nor a script (Java binary properties such as `\p{IsAlphabetic}`).
- Character classes are XML Schema classes. A class cannot be empty, and `]` is a member only when escaped: `[].]` and `[^]]` are rejected (Java and POSIX read the `]` as a member). `-` is a literal at the start or end of a class, after a range and next to a class escape (`[\w-.]`); between two characters it makes a range, whose endpoints may be escapes (`[\x41-\x5A]`) but not an unescaped hyphen (`[a--b]` is rejected; write `\-`). `-[` always starts a subtraction.
- The `i` flag makes characters, character ranges (also in negated classes and subtractions) and back-references case-insensitive, and nothing else (XPath F&O 5.6.2): `\p{Lu}` still matches upper-case letters only, also inside a class such as `[\p{Lu}x]`. `java.util.regex` would make `\p{Lu}` match every cased letter.
- One pattern evaluation (one pattern against one value) has a budget, `ValidationConfig.patternTimeout` (default 1 second). The budget is **counted in steps of the regular expression engine**, 50,000 per millisecond of the duration (fifty million by default), so whether a value is decided does not depend on the machine, its load or a garbage collection pause; the wall clock is only a backstop, at ten times the duration. A value on which a pattern uses up this budget (catastrophic backtracking), or on which the regular expression engine runs out of stack (an alternation under a quantifier such as `(a|b)*` on a very long value), does **not** abort the run. The constraint is *undecided* for that value: the report gets a result that names the pattern, has the shape's severity and `sh:PatternConstraintComponent`, and is marked `isPatternTimeout` / `isPatternTooComplex` (`ksh:resultStatus ksh:PatternTimeout` / `ksh:PatternTooComplex` in RDF). The value is not accepted, so the report cannot conform because of it, and validation continues with the other values and focus nodes. A shape that reads the answer through `sh:not`, `sh:or` and the like gets "undefined" (see [three-valued conformance](#three-valued-conformance)), so an undecided pattern never satisfies or silently fails an outer constraint. With `strictMode = true`, validation throws `ShaclValidationException` naming the pattern instead. Only the run-wide `timeout` aborts a run. The budget is per value, so a run also has a cap: once 16 evaluations of the run have used up their whole budget, every value not matched before gets 1/1024 of it, but at least 1,024 steps and at least 64 steps per character of the value (never more than the full budget). A pattern that does not backtrack reads each character a few times, so an ordinary value of any length (base64, WKT…) is still decided, a hostile one is undecided at a cost linear in its length, and data without hostile values never reaches the cap. Within a run a (pattern, value) has a single answer, whoever asks it (the report, `sh:node`, `sh:not`…); only whether a value that is expensive to match but not hostile is decided can depend on whether it is first matched before or after the cap is reached.
- Stack exhaustion does not depend on the calling thread either. Values of 1,024 characters or more are matched on a dedicated thread with a fixed 16 MiB stack (one per validation run, created when first needed), and a shorter value whose match overflows the caller's stack is matched there again. The same pattern and value therefore get the same answer at any shape nesting depth and for any `-Xss`.
- RDF lists in the shapes graph (`sh:in`, `sh:and` / `sh:or` / `sh:xone`, `sh:languageIn`, `sh:ignoredProperties`, sequence and alternative paths) must be well-formed: a cell with a missing or repeated `rdf:first` / `rdf:rest` is a shape compilation error rather than a silently truncated list. List cells may be blank nodes or IRIs. A sequence path and an alternative path need at least two members: `sh:path ( )` and `sh:path ( ex:p )` are shape compilation errors. The shapes graph is read as a set of triples, so a graph implementation that returns the same triple twice does not make a parameter look repeated.
- Classes that are also shapes act as implicit class targets. Value comparisons (`sh:lessThan`, bounds, …) follow the SPARQL operator mapping for datatypes, and literals are checked for XSD lexical validity.
- Numbers are compared after XPath numeric type promotion: two decimals (or integers) exactly; a decimal and an `xsd:float` as floats; anything with an `xsd:double` as doubles. So `"0.1"^^xsd:float` satisfies `sh:maxInclusive 0.1` (both are the float nearest to 0.1), while it is above `"0.1"^^xsd:double`. Negative zero equals zero, and `NaN` satisfies no range or order constraint (it is not comparable, even to itself). `sh:in` and `sh:hasValue` compare RDF terms, not values: `"0.0"^^xsd:decimal` is not in `sh:in ( 0 )`.
- An ill-formed parameter value is a shape compilation error, not a skipped constraint: a literal as `sh:class` or `sh:nodeKind` value, for example.
- `owl:imports` of the shapes graph are followed only with `ImportConfig(resolveOwlImports = true)`, and only offline: an import is resolved when `DatasetValidationConfig.auxiliaryGraphs` has a graph for its IRI. An import without a graph, or beyond `maxImportDepth`, is skipped and listed in `report.warnings` (`ksh:warning` in RDF), because the shapes it would contribute are not validated.

### Three-valued conformance

The native engine answers every nested conformance check (`sh:node`, logical constraints, qualified value shapes, `sh:someValue`, `sh:targetWhere`, …) with **conforms**, **fails** or **undefined**, and combines the answers with Kleene logic:

- a shape and `sh:and` fail as soon as one part definitely fails;
- `sh:or` and `sh:someValue` conform as soon as one part definitely conforms;
- an answer is undefined only when it really depends on an undefined answer.

As a result, reports never depend on the order of operands, constraints or targets. When one value node is undefined, the definite violations of the other value nodes are still reported, and the undefined value adds an undefined result (see below). Qualified value counts use a lower bound (values that definitely conform) and an upper bound (values that conform or are undefined). A count is undefined only when the two bounds lead to different outcomes.

Undefined answers come from recursive shapes, described next, and from `sh:pattern` evaluations that could not be completed (see [conformance semantics](#conformance-semantics)). A result has one status. When an answer is undefined for both reasons, the result is marked `ksh:UndefinedRecursion` and its message also names the pattern; a constraint with several undefined operands reports undefined recursion in preference to a pattern, whatever the order of the operands.

### Recursive shapes

SHACL does not define recursive shapes. At compile time, the native engine finds the shapes that can reach themselves (the strongly connected components of the shape dependency graph), because only those can recurse over data. It then applies these rules.

**Monotone recursion conforms (greatest fixpoint).** A shape that depends on itself only through `sh:node`, `sh:and`, `sh:property`, `sh:or`, `sh:someValue` or `sh:qualifiedMinCount` is assumed to conform until one of its constraints fails. Valid cyclic data therefore conforms. `sh:shape`, `sh:memberShape`, `sh:reifierShape` and `sh:nodeByExpression` also count as monotone.

```turtle
# Shapes: recursion through sh:or
ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
  sh:property [ sh:path ex:knows ; sh:or ( ex:NamedShape ex:PersonShape ) ] .
ex:NamedShape sh:property [ sh:path ex:name ; sh:minCount 1 ] .

# Data: a cycle. Conforms, also with strictMode = true.
ex:a a ex:Person ; ex:knows ex:b .
ex:b a ex:Person ; ex:knows ex:a .
```

A cycle can still fail. If a node on it definitely breaks a constraint (for example, a `sh:minCount` on `ex:knows` at the end of a chain), the nodes that depend on it get ordinary `sh:Violation` results.

**Recursion through non-monotone operators is undefined.** Consider a dependency cycle that passes through `sh:not`, `sh:xone`, `sh:qualifiedMaxCount` or the sibling exclusion of `sh:qualifiedValueShapesDisjoint true`. It has no defined answer. Each constraint whose outcome depends on that answer produces a result stating that the recursive dependency is undefined. The result has `isUndefinedRecursion == true` (violation code `ValidationViolation.UNDEFINED_RECURSION_CODE`) and keeps the constraint's component and the source shape's declared severity, so it affects `report.isValid` exactly as a failure of that constraint would, whatever `conformanceDisallows` is. The report therefore conforms only when it conforms whatever the undefined answers are. With `ValidationConfig(strictMode = true)`, validation throws `ShaclValidationException` instead. If a non-recursive part of the shape already fails, that failure decides the outcome and no undefined result is produced.

```turtle
# Shapes: ex:S depends on itself through sh:not
ex:S a sh:NodeShape ; sh:targetNode ex:x ;
  sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
  sh:property [ sh:path ex:self ; sh:not ex:S ] .

# Data: one blocking sh:Violation result marked isUndefinedRecursion ("... is undefined"); strictMode throws.
ex:x ex:self ex:x ; ex:name "x" .
```

**Deep data is stack-safe.** Recursive shapes are solved with an explicit worklist, not with JVM stack proportional to the data. For example, the following 10,000-node chain validates. A missing name at its tail is reported for every node of the chain.

```kotlin
val chain = Rdf.graph {
    for (i in 0 until 10_000) {
        val p = iri("http://example.org/p$i")
        p - RDF.type - iri("http://example.org/Person")
        p - iri("http://example.org/name") - string("person $i")
        if (i + 1 < 10_000) p - iri("http://example.org/knows") - iri("http://example.org/p${i + 1}")
    }
}
// PersonShape: sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] (plus a name constraint)
val report = ShaclValidation.validator().validate(chain, shapesGraph)
```

**`maxRecursionDepth` limits non-recursive nesting only.** `ValidationConfig.maxRecursionDepth` (default `64`) limits how deeply shapes that are **not** recursive can nest through `sh:node`, logical constraints and similar references. Recursive shapes are solved as described above and are not bound by it. Exceeding the limit throws `ShaclValidationException`.

```kotlin
val validator = ShaclValidation.validator(ValidationConfig(maxRecursionDepth = 128))
```

### `sh:targetWhere`

The native engine checks every node of the data graph against the membership shape of `sh:targetWhere`. It first prunes candidates using the membership node shape's own constraints:
- with `sh:class`, only instances of that class are checked;
- with `sh:nodeKind`, the node kinds it excludes (literals or non-literals) are skipped;
- with `sh:datatype`, only literals are checked.

When a candidate's membership is undefined (recursion through a non-monotone operator), the report gets an undefined result (`isUndefinedRecursion`) for that candidate with the target shape's severity. It blocks conformance whenever a failure of that shape would. With `strictMode = true`, validation throws `ShaclValidationException`. SPARQL node expressions used as `sh:targetWhere` values are not supported; see [Unsupported features](#unsupported-features).

### Report contents

`ValidationViolation` carries the full result path (`resultPathNode`, plus `resultPathTriples` for blank-node paths), the shape's `sh:message` values with language tags (`resultMessages`) and `sourceConstraint` (for example, the SHACL-SPARQL constraint node). `toShaclValidationReportRdf()` emits `sh:resultPath`, `sh:resultMessage` and `sh:sourceConstraint`, emits `sh:value` only for components that define it, and emits one `sh:closed` result per offending triple.

What the SHACL report vocabulary cannot express is exported with Kastor extension terms (`KastorShaclVocabulary`, prefix `ksh:`, namespace `https://kastor.geoknoesis.com/ns/shacl#`). They are additional triples on the `sh:ValidationReport` and its `sh:ValidationResult` nodes; nothing is minted in the `sh:` namespace, and consumers of the standard vocabulary can ignore them.

| Term | Meaning |
|------|---------|
| `ksh:resultStatus` | On a result: the constraint was **not decided**, which is not the same as a failure (`violation.isUndecided`, `violation.resultStatus`). The result has the severity and constraint component a failure would have, so without this triple an RDF consumer could not tell the two apart. Its value is one of the three statuses below. |
| `ksh:UndefinedRecursion` | The constraint depends on a recursive shape dependency through a non-monotone operator (`isUndefinedRecursion`). |
| `ksh:PatternTimeout` | A `sh:pattern` evaluation used up its budget (`ValidationConfig.patternTimeout`, counted in regular expression engine steps) on a value (`isPatternTimeout`). |
| `ksh:PatternTooComplex` | The regular expression engine ran out of stack while matching a `sh:pattern` (`isPatternTooComplex`). |
| `ksh:reifier` | On a `sh:reifierShape` result: the reifier that does not conform, or whose conformance is undecided. `sh:value` is the object of the reified triple, so several reifiers of one triple differ only by this property (and by the engine message). In Kotlin it is `violation.context[ValidationViolation.REIFIER_CONTEXT_KEY]`. |
| `ksh:warning` | On the report: the message of a report-level warning (`report.warnings` entries without a resource), for example a construct skipped under `IGNORE_WITH_WARNING`. Warnings do not affect `sh:conforms`; this property keeps a conforming RDF report from hiding what was not validated. |

The vocabulary is published as a Turtle document inside the `rdf-shacl-validation` JAR, at the classpath resource `KastorShaclVocabulary.VOCABULARY_RESOURCE` (`/com/geoknoesis/kastor/rdf/shacl/kastor-shacl.ttl`). Every term has an `rdfs:label`, an `rdfs:comment` and `rdfs:isDefinedBy`; `ksh:ResultStatus` is the class of the three statuses. The document has an `owl:versionInfo` (`KastorShaclVocabulary.VERSION`); the namespace IRI is stable and carries no version, and terms are only ever added. `KastorShaclVocabulary.terms` lists the terms, and a test checks that every `ksh:` term the engine emits is declared in the document.

```kotlin
val vocabulary = KastorShaclVocabulary::class.java.getResourceAsStream(KastorShaclVocabulary.VOCABULARY_RESOURCE)!!
    .use { Rdf.parse(it.readBytes().decodeToString(), RdfFormat.TURTLE) }
```

Each failing reifier yields its own result; results that would be identical are reported once. `sh:reificationRequired` is a parameter of `sh:ReifierShapeConstraintComponent`, which is the component reported for both `ConstraintType.REIFIER_SHAPE` and `ConstraintType.REIFICATION_REQUIRED` results.

### SHACL-SPARQL

`sh:sparql` constraints need a SPARQL engine **at runtime**: add `rdf-jena` or `rdf-rdf4j`. Without one, validation fails with a `ShaclValidationException` naming those modules, and the native provider's capability flags report SHACL-SPARQL as unsupported. Queries:

- run over the **data graph** (see [where queries run](#where-queries-run));
- honour `sh:prefixes`/`sh:declare`, `$PATH`, `sh:deactivated` and message templates;
- pre-bind `$this` (as in SHACL 1.2, `$currentShape` and `$shapesGraph` are not pre-bound: they are ordinary variables);
- produce one result per solution row, with `sh:sourceConstraint`.

A `sh:sparql` constraint may set `sh:severity`, which becomes the severity of its results, and `sh:resultAnnotation` (`sh:annotationProperty`, `sh:annotationVarName`, `sh:annotationValue`) to add annotation triples to them.

#### Prefix declarations

`sh:prefixes` / `sh:declare` are followed through `owl:imports`, including `owl:versionIRI` navigation. A node with no `sh:prefixes` uses the graph-level declarations of the SHACL instances of `owl:Ontology`, `sh:DataGraph`, `sh:ShapesGraph` and `sh:RulesGraph`. Conflicting declarations, declarations with the wrong datatype, and an `sh:prefix` that is not a SPARQL prefix name (`PN_PREFIX`: empty, or a letter followed by letters, digits, `_`, `-` or `.`, not ending in `.`) make the shapes graph ill-formed. Kastor writes each declaration into the query prologue, so this check stops a prefix from injecting query text.

#### SPARQL-based constraint components

A `sh:ConstraintComponent` with `sh:parameter` (and `sh:optional`) is evaluated like a built-in component:

- **Validators:** SELECT validators come from `sh:nodeValidator` / `sh:propertyValidator`, ASK validators from `sh:validator`. The validator is chosen as in spec section 4.2.3; a component with no validator that fits the shape kind is ignored.
- **Parameters:** parameter values are pre-bound. A shape with several values for a parameter yields one constraint per combination, up to 1024 combinations per shape and component; more than that makes the shapes graph ill-formed.
- **Results:** results report `sh:sourceConstraintComponent` as the component IRI. The message comes from the validator, else the component, else the shape, with `{$param}` substitution.

#### SPARQL node expressions

`sh:select` and `sh:sparqlExpr` are supported:

- at `sh:targetNode`, with `$this` bound to the shape node;
- at `sh:values` and `sh:defaultValue`, with `$this` bound to the focus node.

Constant IRI and literal values also work at `sh:values` and `sh:defaultValue`. The value nodes of a property shape are the path values plus the `sh:values` outputs, and `sh:defaultValue` applies when that set is empty. Both are allowed only on predicate paths. An IRI node that carries `sh:select` / `sh:sparqlExpr` and is used there is ill-formed.

#### SPARQL functions

A `sh:ListParameterExpressionFunction` whose `sh:bodyExpression` is a `sh:select` or `sh:sparqlExpr` can be called, by its IRI, from any SHACL-SPARQL query (spec section 7). The engine compiles each declaration and sends the compiled functions with every SHACL-SPARQL query. Jena and RDF4J make them visible to that one query only; no global function registry is ever modified, and a declaration cannot redefine a built-in function (the built-in wins).

- **Parameters:** `shnex:arg0`, `shnex:arg1`, ... form a contiguous run starting at 0, and an optional parameter (`sh:optional true`) cannot be followed by a mandatory one. Otherwise the shapes graph is ill-formed (`ShapeCompileException`).
- **Body:** `$this` is the function IRI and `$arg<i>` holds each argument. An argument that is absent or failed to evaluate stays unbound if its parameter is optional; otherwise the call is an error.
- **Errors:** a blank-node argument, too many arguments, and a body with no result or several results are errors, as is a call to an unknown function IRI. A function error is an ordinary SPARQL error: `BIND` leaves its variable unbound and `FILTER` is false. It never fails validation.
- **Nesting:** functions can call each other, up to 16 levels deep (`MAX_FUNCTION_DEPTH`); a deeper call is an error, so recursion terminates. Nested queries are sandboxed like the outer one (no `SERVICE`, no `java:`) and run on the calling query's dataset; their own `FROM` can only select graphs inside it. A timeout stops the whole query. Time is the only bound on how many nested calls a query can make (fan-out); SHACL queries always run under the validation timeout.

Limits:
- On RDF4J, work that reads no statements (a `VALUES` cross product, a catastrophic `REGEX`) is not interrupted within a row; top-level RDF4J queries have the same limit. Jena checks cancellation per binding.
- On RDF4J, only stores created by Kastor's factories evaluate custom functions.
- Parameter constraints (`sh:datatype`, ...) and `sh:defaultValue` are not enforced, as the spec says.
- Function bodies written as `shnex:` node expressions (spec section 7.1, such as the W3C `instanceCount` example) are not supported: see `SHACL_FUNCTION` below.

#### Where queries run

When you validate a SPARQL-capable dataset, such as a repository passed to `validateDataset`, queries run on it **in place** only if the dataset lists **no named graphs**. Otherwise the engine copies the data graph into a private in-memory repository once per validation run, and all SPARQL constraints of that run share the copy. This also happens when validating a plain graph. The reason is that a query without a dataset clause may see more than the default graph: on RDF4J it sees the union of all contexts. SHACL-SPARQL constraints must see exactly the data graph that the other constraints validate. Declaring any SPARQL-bodied function, even an unused one, always uses the private copy, on every provider. Each kind of fallback is logged once per dataset class. The copy is not reused across runs; to validate a large graph repeatedly, validate a repository without named graphs in place.

#### Pre-binding

The engine pre-binds variables itself before the query reaches the provider, so the result is the same on every provider, including inside sub-queries:

- IRIs and literals are substituted **textually** into the query text. In a `SELECT` projection a value becomes `(term AS ?fresh)`, and `BOUND(?var)` on a substituted variable becomes `true`.
- Values with no equivalent SPARQL syntax are passed to the provider as initial bindings: blank nodes, triple terms, directional language strings, and IRIs containing characters that are illegal in an `IRIREF`.

A query that breaks the SHACL-SPARQL pre-binding restrictions is rejected when the shapes are compiled. The restrictions are:
- no `MINUS` or `SERVICE`;
- no `VALUES` that mentions `this`, a parameter, or (in ASK queries) `value`;
- no `AS` that re-binds `this`, a parameter, or (in ASK queries) `value`.

The restrictions are checked on the query text and again on the full query with its `PREFIX` prologue.

The provider enforces the same boundary at run time. Every SHACL-SPARQL query reaches it as a `SandboxedSelectQuery`, which the Jena and RDF4J providers parse with their own parser and refuse before evaluation if it contains `SERVICE` anywhere, or (on Jena) a `java:` function or property function, which would load a class by name. Validation then throws `ShaclValidationException`. A remote SPARQL endpoint cannot be checked, so a dataset backed by one is validated on a private in-memory copy of the data graph instead.

Sub-selects need not project `$this` (SHACL 1.2 removed that restriction). One limit remains: a blank-node value used inside a sub-select that does not project it cannot be passed to the provider. The provider contract rejects it, and validation throws `ShaclValidationException`.

`$PATH` (either `$` or `?`) is substituted on property shapes and left unbound on node shapes.

Other `VALUES` clauses are allowed, as SHACL 1.2 Appendix A specifies. The approved W3C test `unsupported-sparql-002` still expects any `VALUES` to fail (the SHACL 1.0 rule); the harness lists it as a spec override (see below).

Validation then throws `ShaclValidationException` ("SHACL compile failed: ..."), with a `SparqlPreBindingRestrictionException` as its cause.

### Unsupported features

The native engine recognises some SHACL constructs that it cannot evaluate. By default (`ValidationConfig.unsupportedFeatures = UnsupportedFeatureHandling.FAIL`), it rejects the shapes graph before validating anything, so these constraints are never silently skipped.

| `UnsupportedShaclFeature` | Constructs |
|---------------------------|------------|
| `SPARQL_CONSTRAINT_COMPONENT` | None: SPARQL-based constraint components are supported (see above). The category is kept for compatibility |
| `NODE_EXPRESSION` | SHACL 1.2 node expressions the engine cannot evaluate: `shnex:` and other non-SPARQL node expressions at `sh:values`, `sh:defaultValue` and `sh:targetNode`, `sh:expression`, non-constant `sh:deactivated`, and any blank-node `sh:nodeByExpression` value (a SPARQL one is reported as `SPARQL_NODE_EXPRESSION`) |
| `SPARQL_NODE_EXPRESSION` | SPARQL node expressions (`sh:select`, `sh:sparqlExpr`) used as `sh:targetWhere` values or as blank-node `sh:nodeByExpression` values. At `sh:targetNode`, `sh:values` and `sh:defaultValue` they are supported |
| `SHACL_FUNCTION` | SHACL 1.2 functions whose `sh:bodyExpression` is a `shnex:` node expression (spec section 7.1), called from a SPARQL query. Functions with a `sh:select` / `sh:sparqlExpr` body are supported (see above) |
| `CUSTOM_TARGET` | SPARQL-based or custom targets (`sh:target`) |
| `REIFIER_CONSTRAINT_ON_COMPLEX_PATH` | `sh:reifierShape` / `sh:reificationRequired true` on a property shape whose `sh:path` is not a predicate IRI (the engine only knows which triples a predicate path traverses) |

`sh:sparql` constraints, SPARQL-based constraint components, SPARQL node expressions at `sh:targetNode`, `sh:values` and `sh:defaultValue`, and SPARQL functions with a SPARQL body (see above) are supported.

In the failure, `ShaclValidationException` wraps an `UnsupportedShaclFeatureException`. Its message names each offending construct, and its `features` property lists the categories:

```kotlin
try {
    validator.validate(dataGraph, shapesGraph)
} catch (e: ShaclValidationException) {
    val unsupported = e.cause as? UnsupportedShaclFeatureException ?: throw e
    println("Unsupported: ${unsupported.features}")
}
```

To skip these constructs instead, set `ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)`. Each skipped construct is listed in `report.warnings` ("Unsupported SHACL feature ignored: ..."). These are report warnings, not validation results, so they do not change `isValid`. `toShaclValidationReportRdf()` exports them as `ksh:warning` literals on the report node, so the RDF report does not say `sh:conforms true` without a trace of what was skipped. `ValidationConfig(includeWarnings = false)` leaves them out of the report (and of its RDF export).

Detection only inspects declared and implicit shapes, plus the nodes reachable from them through shape-valued parameters. Data triples that share the shapes graph are therefore never mistaken for expressions. For example, `validate(g, g)` works when `g` has blank-node `sh:targetNode` values that carry data triples.

**Blank node `sh:targetNode` values.** In SHACL 1.2 the values of `sh:targetNode` are node expressions: an IRI or a literal is a constant, a blank node is a computed expression. A blank node of the shapes graph can only denote a data node when the two graphs share it (the same graph validated against itself, or shapes discovered from the data). The engine therefore applies this rule:

1. A blank node whose own triples use vocabulary that has no reading as data is a node expression (`NODE_EXPRESSION` / `SPARQL_NODE_EXPRESSION`), whatever the data graph is: a SPARQL expression (`sh:select`, `sh:sparqlExpr`), the `shnex:` or `sparql:` namespaces as predicate or type (`shnex:pathValues`, `sparql:concat ( … )`), or a call `[ ex:fn ( … ) ]` of a function declared in the shapes graph.
2. Any other blank node is an **ordinary target if it is a node of the data graph**, regardless of its predicates: a plain node, an RDF list cell, a node that carries `sh:` predicates or a SHACL type (a shape used as data), or a call-shaped node whose function is not declared.
3. Otherwise it can never be a focus node, and the shape would silently go unvalidated. It is reported as an unsupported `NODE_EXPRESSION`, like the expressions of rule 1: a failure by default, a report warning with `IGNORE_WITH_WARNING`. This is the same for every such blank node, whatever its triples look like.

### Configuration options that are rejected or have no effect

- `parallelValidation = true` and `streamingMode = true` are not supported by the native engine (provider ids `kastor` and `memory`). Creating a validator with either throws `UnsupportedShaclOperationException`, a `ShaclValidationException`.
- `validate(graph, List<ShaclShape>)` and `validateConstraints(graph, constraints)` throw the same exception for a non-empty list: pass the shapes as an RDF graph. The RDF4J bridge throws `UnsupportedOperationException` for these two calls.
- `includeWarnings = false` empties `report.warnings` and nothing else: results are reported whatever their severity, including `sh:Warning`.
- `batchSize`, `enableExplanations`, `enableSuggestions` and `validateInactiveShapes` are deprecated. No engine ever read them: validation is not batched, `explanation` and `suggestedFix` are never filled, and a shape with `sh:deactivated true` is never validated. They remain only for binary compatibility.

### W3C conformance

CI runs the **full W3C SHACL 1.2 test suite** (core, node-expression and SPARQL manifests) against the native engine. The suite comes from a pinned `w3c/data-shapes` commit (`94d8bc2`, 166 cases). To run it locally:

```bash
python scripts/fetch-conformance-data.py --only shacl
./gradlew :rdf:shacl-validation:w3cConformanceTest
```

The task fails instead of skipping when the suite is missing, and every case is executed. The default `test` task runs the same harness against the bundled fixture when the checkout is missing; a missing manifest, a manifest without `sht:Validate` entries or one without an approved case fails the test rather than skipping it, unless `-PshaclW3cAllowMissingData=true` opts out. Manifest rows that are not approved are skipped unless `-PshaclW3cIncludeNonApproved=true`. The harness checks failures strictly:
- The deviations are real feature gaps, kept visible on purpose: SHACL 1.2 node expression constraints (`sh:expression`) and SHACL functions with a `shnex:` body (spec section 7.1) need an expression evaluator that the engine does not have, and the SHACL 1.2 drafts they come from are still changing. They fail with a categorised error instead of passing silently.
- Each known deviation in `W3cKnownDeviations` has an `UnsupportedShaclFeature` category and must fail with an `UnsupportedShaclFeatureException` for exactly that category. At the pinned commit there are 2: 1 node expression (`node-expr/constraints/expression-001`) and 1 SHACL function (`sparql/functions/instanceCount-example`, a `shnex:` body).
- Spec overrides in `W3cKnownDeviations` are approved cases whose expected result contradicts the normative SHACL 1.2 text. The engine follows the text, and the case must validate without failing. There is 1: `unsupported-sparql-002` expects any `VALUES` to fail, but SHACL 1.2 Appendix A only forbids `VALUES` that mentions a pre-bound variable.
- `sht:Failure` cases must fail with their expected category (a pre-binding restriction or a shapes compile failure), never with an unsupported feature.
- `sh:conformanceDisallows` cases check the engine's own `isValid` and exported `sh:conforms`.
- Result graphs are compared after removing the `ksh:` triples, so an undecided result would look like a failure. A case therefore fails if the engine emits any result with a `ksh:resultStatus` (undefined recursion, pattern timeout, pattern too complex), unless the case is listed with a reason in `W3cKnownDeviations`. No case is listed: the engine decides every constraint of the suite.

See the [module README](../../../rdf/shacl/validation/README.md).

### RDF4J validator

With `providerId = "rdf4j"`, `isValid` comes from the engine's `sh:conforms`, read before any `maxViolations` cap is applied. `validateResource` validates the whole graph and then keeps only the results for the requested focus node.

> **Tip**: Use the [SHACL DSL](../api/shacl-dsl-guide.md) to create shapes graphs more easily. The DSL supports all SHACL 1.2 features including:
> - **SHACL 1.2 Core**: `targetWhere` with node expressions, `shape` targets, `singleLine` constraint, `reifierShape` and `reificationRequired` for RDF-star
> - **SHACL 1.2 SPARQL Extensions**: SPARQL-based constraints using SELECT queries (`sparqlAsk` was removed: `sh:sparql` constraints cannot use ASK; ASK is available only as a constraint component `sh:validator`)
>
> See [How to Create SHACL Shapes](../guides/how-to-create-shacl-shapes.md) for examples. For **bundled ontology-quality shapes**, the `onto-qa` CLI, and the optional embedding tier, see [How to Check Ontology Quality](../guides/how-to-ontology-quality.md).

## Notes

- `ValidationContext` is only enforced when provided.
- `ValidationResult.NotConfigured` is returned when no context is available.
- Repository-level validation remains independent of Kastor Gen materialization.
