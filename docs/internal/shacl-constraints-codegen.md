# SHACL Constraint Mapping to Code Generation (Detailed Design)

## Purpose
Define a complete, deterministic mapping from SHACL constraints to generated code in Kastor Gen. This includes:
- data model extensions to represent SHACL features,
- parser extraction rules,
- interface/implementation generation rules,
- optional runtime validation strategies,
- and test coverage requirements.

This document targets a **complete** SHACL constraint coverage. Where constraints cannot be expressed in static types, a runtime validation strategy is specified.

## Scope
Covered SHACL features:
- Node shapes (`sh:NodeShape`) and property shapes (`sh:property`)
- Cardinality (`sh:minCount`, `sh:maxCount`)
- Value type constraints (`sh:datatype`, `sh:class`, `sh:nodeKind`)
- String constraints (`sh:minLength`, `sh:maxLength`, `sh:pattern`, `sh:languageIn`, `sh:uniqueLang`)
- Numeric constraints (`sh:minInclusive`, `sh:maxInclusive`, `sh:minExclusive`, `sh:maxExclusive`, `sh:totalDigits`, `sh:fractionDigits`)
- Value set constraints (`sh:in`, `sh:hasValue`)
- Logical constraints (`sh:and`, `sh:or`, `sh:xone`, `sh:not`)
- Value comparison constraints (`sh:equals`, `sh:disjoint`, `sh:lessThan`, `sh:lessThanOrEquals`)
- Qualified value shape constraints (`sh:qualifiedValueShape`, `sh:qualifiedMinCount`, `sh:qualifiedMaxCount`)
- Shape-based constraints (`sh:node`, `sh:property` with nested shapes)
- Path constraints (simple IRI + complex paths)

Out of scope:
- SHACL rules (`sh:rule`) and inference execution.
- SHACL advanced features requiring SPARQL execution unless explicitly mapped.

## Current State (Baseline)
Today’s parser and generators only extract a subset:
`sh:path`, `sh:name`, `sh:description`, `sh:datatype`, `sh:class`, `sh:minCount`, `sh:maxCount`.
This must be expanded to full SHACL coverage as defined below.

## Design Goals
- **Predictable** mapping of constraints to generated code.
- **Type safety** where possible; **runtime validation** where necessary.
- **No silent constraint loss**: every SHACL constraint must be represented either
  as static type, annotation metadata, or runtime validation code.
- **Extensible**: new constraints can be added without breaking existing generators.

## Data Model Extensions
Add a richer SHACL model in `kastor-gen/processor/.../model`:

```kotlin
data class ShaclShape(
  val shapeIri: String,
  val targetClass: String,
  val properties: List<ShaclProperty>,
  val constraints: List<ShapeConstraint> = emptyList()
)

data class ShaclProperty(
  val path: ShaclPath,
  val name: String,
  val description: String,
  val datatype: String?,
  val targetClass: String?,
  val minCount: Int?,
  val maxCount: Int?,
  val nodeKind: NodeKind?,
  val constraints: List<PropertyConstraint> = emptyList()
)

sealed interface ShaclPath
data class IriPath(val iri: String) : ShaclPath
data class SequencePath(val items: List<ShaclPath>) : ShaclPath
data class AlternativePath(val items: List<ShaclPath>) : ShaclPath
data class InversePath(val item: ShaclPath) : ShaclPath
data class ZeroOrMorePath(val item: ShaclPath) : ShaclPath
data class OneOrMorePath(val item: ShaclPath) : ShaclPath
data class ZeroOrOnePath(val item: ShaclPath) : ShaclPath

enum class NodeKind { IRI, BlankNode, Literal, BlankNodeOrIRI, BlankNodeOrLiteral, IRIOrLiteral }

sealed interface PropertyConstraint
data class Pattern(val regex: String, val flags: String?) : PropertyConstraint
data class MinLength(val value: Int) : PropertyConstraint
data class MaxLength(val value: Int) : PropertyConstraint
data class LanguageIn(val tags: List<String>) : PropertyConstraint
data class UniqueLang(val value: Boolean) : PropertyConstraint
data class InSet(val values: List<String>) : PropertyConstraint
data class HasValue(val value: String) : PropertyConstraint
data class NumericRange(val minInc: Double?, val maxInc: Double?, val minEx: Double?, val maxEx: Double?) : PropertyConstraint
data class NumericPrecision(val totalDigits: Int?, val fractionDigits: Int?) : PropertyConstraint
data class Equals(val path: ShaclPath) : PropertyConstraint
data class Disjoint(val path: ShaclPath) : PropertyConstraint
data class LessThan(val path: ShaclPath, val orEquals: Boolean) : PropertyConstraint
data class NodeConstraint(val shapeRef: String) : PropertyConstraint
data class QualifiedValueShape(
  val shapeRef: String,
  val min: Int?,
  val max: Int?,
  val disjoint: Boolean
) : PropertyConstraint

sealed interface ShapeConstraint
data class And(val shapes: List<String>) : ShapeConstraint
data class Or(val shapes: List<String>) : ShapeConstraint
data class Xone(val shapes: List<String>) : ShapeConstraint
data class Not(val shape: String) : ShapeConstraint
```

## Parsing Rules (SHACL → Model)
For each `sh:property` blank node:
1. Parse `sh:path`:
   - IRI → `IriPath`
   - `sh:alternativePath` → `AlternativePath`
   - `sh:sequencePath` → `SequencePath`
   - `sh:inversePath` → `InversePath`
   - `sh:zeroOrMorePath`, `sh:oneOrMorePath`, `sh:zeroOrOnePath` → corresponding path nodes
2. Parse `sh:nodeKind` → `NodeKind`
3. Parse datatype/class/cardinality as before
4. Parse all constraint predicates into `PropertyConstraint`
5. Parse `sh:node` as `NodeConstraint` referencing a shape IRI
6. Parse `sh:qualifiedValueShape`, `sh:qualifiedMinCount`, `sh:qualifiedMaxCount`, `sh:qualifiedValueShapesDisjoint`

For each `sh:NodeShape`:
1. Parse `sh:targetClass`
2. Parse logical constraints (`sh:and`, `sh:or`, `sh:xone`, `sh:not`) into `ShapeConstraint`

## Code Generation Strategy
### 1) Interface Generation (Type-Level)
Rules:
- If `maxCount` is `null` or > 1 → `List<T>`
- If `maxCount == 1` and `minCount` is `0` or `null` → `T?`
- If `maxCount == 1` and `minCount >= 1` → `T`
- For `nodeKind`:
  - `Literal` → map to scalar types only
  - `IRI`/`BlankNode` → map to object types or `Iri` when `datatype` is absent

### 2) Wrapper Generation (Runtime)
Implement accessors using:
- Required values: `getRequiredLiteralValue` / `firstOrNull() ?: error(...)`
- Optional values: `firstOrNull()` or nullable
- Lists: `getLiteralValues` / `getObjectValues`

### 3) Validation Code Generation
Add a **validator layer** optionally generated:
- `interface` annotations (Java/Jakarta validation) where possible
- `validate()` method in wrapper (or a dedicated validator) for runtime checks

#### Mapping Table: SHACL → Validation
| SHACL Constraint | Interface Annotation | Runtime Validation |
|---|---|---|
| `minCount >= 1` | `@NotNull` / `@NotEmpty` | Required value check |
| `maxCount = 1` | — | Ensure single value |
| `minLength` | `@Size(min=…)` | String length check |
| `maxLength` | `@Size(max=…)` | String length check |
| `pattern` | `@Pattern` | Regex match |
| `languageIn` | — | Language tag check |
| `uniqueLang` | — | Unique language tags |
| `in` | — | Membership check |
| `hasValue` | — | Equality check |
| `minInclusive/maxInclusive` | `@Min/@Max` (numeric) | Range check |
| `minExclusive/maxExclusive` | — | Range check |
| `totalDigits/fractionDigits` | — | Precision check |
| `equals/disjoint/lessThan` | — | Cross-property validation |
| `and/or/xone/not` | — | Shape-level validation |
| `qualifiedValueShape` | — | Subshape validation |

**Note**: Not all constraints map to standard Java annotations; runtime validation is required for cross-property and shape-level constraints.

### 4) Complex Paths
Only `IriPath` should map to direct property access. Complex paths require
runtime evaluation in `KastorGraphOps` or an internal path evaluation helper:
- If a path is complex → generate a helper that resolves it at runtime and apply constraints against that derived value set.

## Runtime Validation API
Introduce (design):

```kotlin
interface ValidationContext {
  fun validate(shape: ShaclShape, handle: RdfHandle): List<Violation>
}

data class Violation(
  val path: String?,
  val message: String,
  val severity: String = "Violation"
)
```

Generated wrappers can provide:
```kotlin
fun validate(): List<Violation>
```

## Validator Configuration
Support generator options to choose where validation lives:

```kotlin
enum class ValidationMode {
  EMBEDDED,   // generate validate() in wrappers
  EXTERNAL,   // emit metadata, rely on external validator
  NONE        // no validation generated
}
```

Recommended behavior:
- **EMBEDDED**: Generate `validate()` in wrappers using the built-in checks.
- **EXTERNAL**: Generate constraint metadata plus a lightweight adapter to call an injected validator.
- **NONE**: Skip validation code generation entirely.

External validator contract:
```kotlin
interface ValidationContext {
  fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

## Validation Annotation Strategy
Provide a generator option:
```
validationAnnotations = JAKARTA | JAVAX | NONE
```

Default: **JAKARTA** (`jakarta.validation`). Allow **JAVAX** for legacy systems.

Mapping rules:
- `minCount >= 1` → `@NotNull` / `@NotEmpty` (lists)
- `minLength` / `maxLength` → `@Size`
- `pattern` → `@Pattern`
- numeric min/max → `@Min` / `@Max` (integer types only)

All other constraints remain runtime-only.

## Usage Examples
### Embedded Validation (default)
```kotlin
@GenerateFromOntology(
  shaclPath = "shapes.ttl",
  contextPath = "context.jsonld",
  validationMode = ValidationMode.EMBEDDED,
  validationAnnotations = ValidationAnnotations.JAKARTA
)
class OntologyGen
```

### External Validation (custom adapter)
```kotlin
@GenerateFromOntology(
  shaclPath = "shapes.ttl",
  contextPath = "context.jsonld",
  validationMode = ValidationMode.EXTERNAL,
  validationAnnotations = ValidationAnnotations.JAKARTA,
  externalValidatorClass = "com.example.MyValidationContext"
)
class OntologyGen
```

### No Validation
```kotlin
@GenerateFromOntology(
  shaclPath = "shapes.ttl",
  contextPath = "context.jsonld",
  validationMode = ValidationMode.NONE,
  validationAnnotations = ValidationAnnotations.NONE
)
class OntologyGen
```

## Error Strategy
- **Strict mode**: violations throw `ValidationException`.
- **Lenient mode**: collect violations and return.
- Provide configuration flag at generator level to emit strict vs lenient logic.

## Implementation Plan
1. **Model extension** (`ShaclModel.kt`)
2. **Parser expansion** (`ShaclParser.kt`) to cover all constraints and complex paths
3. **Interface generator updates** (type rules and annotations)
4. **Wrapper generator updates** (runtime checks and path evaluation)
5. **Validation runtime utilities** (common checks to avoid duplication)
6. **Tests**:
   - Parser tests for each constraint
   - Generator tests verifying annotations and runtime checks
   - End-to-end tests with sample SHACL files

## Test Coverage Matrix
Each constraint should have at least:
- Parser test
- Interface generation test (if maps to type/annotation)
- Wrapper validation test

## Backward Compatibility
This design is additive. Existing subset behavior remains, with extra constraints producing
additional annotations and validation logic.

## Open Questions
- Should validation annotations be Jakarta (`jakarta.validation`) or javax (`javax.validation`)?
- Should complex paths be fully supported or gated behind feature flags?
- Should we generate separate validator classes instead of embedding `validate()` in wrappers?




