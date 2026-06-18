# kastor-gen Enum Generation — Design

**Date:** 2026-06-17
**Status:** Approved (design); implementation pending
**Component:** `kastor-gen` (KSP/Gradle SHACL→Kotlin generator)

## Context

kastor-gen generates Kotlin domain interfaces, RDF-backed wrappers, immutable data-class
snapshots, and a write-back path from a SHACL shapes graph plus a JSON-LD context. Today it
does **not** generate enumerations: a property constrained to a closed value set via `sh:in`
is parsed (`ShaclProperty.inValues: List<String>`) but typed as `String`, losing the
closed-set typing that PRISM Level 1 (`prism:CoreModel`) mandates (PRISM spec §5.4, §27 L1;
see also Appendix D.2 "path to Level 1 conformance").

Two facts shape the design:

1. **kastor-gen reads SHACL + JSON-LD, not OWL.** The PRISM canonical enum model is an OWL
   `prism:Enumeration` class with `owl:NamedIndividual` members. kastor-gen never sees those,
   so v1 must derive enums from SHACL.
2. **`sh:in` is lossy today.** The parser flattens members to `List<String>` via
   `it.string ?: it.resource?.uri`, so literal members and IRI (named-individual) members are
   indistinguishable, and there is no enum *class name* anywhere (it is a per-property list).

## Goals

- Generate type-safe Kotlin enum types from SHACL-described closed value sets.
- Be predictable: no name collisions, no silent breaking type changes for existing users.
- Be round-trip-safe given the write-back path: never lose an unrecognized value.
- Keep the door open for an OWL-driven source later without reworking the generators.

## Non-goals (YAGNI)

- An OWL ontology reader (only the *seam* for it is built now).
- Property-name-derived enum names (rejected: collisions, surprise breakage).
- Mixed IRI+literal members in a single enum.
- Flag/bitset enums; enum-of-enums.
- Cross-package enum deduplication beyond same-package.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Hybrid architecture**: SHACL source now, behind an `EnumModel` seam; OWL source pluggable later. | Fits current architecture; avoids reworking generators when an OWL reader arrives. |
| D2 | **Explicit name source required**: enum only when `sh:in` is present AND a name resolves from `sh:class` (preferred) or a JSON-LD type mapping. Otherwise stay `String`. | Predictable, collision-free, opt-in via `sh:class`. No behavior change for authors who add no name source. |
| D3 | **Both member kinds**: IRI named-individuals and literal codes; `EnumModel` records per-member kind. | Covers the spec's named-individual model and code-list enums. |
| D4 | **Sealed `Known` + `Unknown`**: a value outside the set is preserved as `Unknown`, not dropped or thrown. | Forward-compatible (PRISM P9); round-trip-safe given the write-back path. |

## 1. Generated shape

IRI-membered enum (name `DocumentStatus`, members `ex:DRAFT`, `ex:ACTIVE`):

```kotlin
sealed interface DocumentStatus {
    val iri: Iri
    enum class Known(override val iri: Iri) : DocumentStatus {
        DRAFT(Iri("https://example.com/ontologies/document#DRAFT")),
        ACTIVE(Iri("https://example.com/ontologies/document#ACTIVE"));
    }
    data class Unknown(override val iri: Iri) : DocumentStatus
    companion object {
        fun from(iri: Iri): DocumentStatus =
            Known.entries.firstOrNull { it.iri == iri } ?: Unknown(iri)
    }
}
```

Literal-membered enum (name `Priority`, members `"LOW"`, `"HIGH"` with `xsd:string`):

```kotlin
sealed interface Priority {
    val code: String
    enum class Known(override val code: String) : Priority {
        LOW("LOW"),
        HIGH("HIGH");
    }
    data class Unknown(override val code: String) : Priority
    companion object {
        fun from(code: String): Priority =
            Known.entries.firstOrNull { it.code == code } ?: Unknown(code)
    }
}
```

Notes:
- The sealed interface exposes a uniform accessor (`iri` for IRI enums, `code` for literal
  enums) so read/write/round-trip is uniform across `Known`/`Unknown`.
- `Known.entries` is the Kotlin stdlib enum entries accessor.
- For literal enums the member `datatype` (when not `xsd:string`) is recorded in `EnumModel`
  for the write path; the carried Kotlin value remains `String` (v1 keeps it simple).

## 2. The `EnumModel` seam

```kotlin
enum class EnumMemberKind { IRI, LITERAL }

data class EnumMember(
    val constantName: String,   // Kotlin identifier, UPPER_SNAKE
    val iri: String? = null,    // set when kind == IRI
    val code: String? = null,   // set when kind == LITERAL
    val datatype: String? = null // literal datatype IRI, when kind == LITERAL and not xsd:string
)

data class EnumModel(
    val name: String,            // Kotlin type name, PascalCase
    val classIri: String?,       // sh:class IRI when that was the name source, else null
    val memberKind: EnumMemberKind,
    val members: List<EnumMember>
)
```

**v1 source — `ShaclEnumExtractor`**: given parsed shapes + the JSON-LD context, it produces
`List<EnumModel>` and a lookup `(shapeIri, propertyPath) → enumName`. A future
`OwlEnumExtractor` produces the same `EnumModel`s from `prism:Enumeration` classes; the
generators depend only on `EnumModel` and the lookup, so they do not change.

## 3. Detection, naming, member rules

- **Trigger:** a property has `sh:in` AND a name source resolves (D2).
- **Member kind:** an `sh:in` entry that is an IRI → `IRI`; a literal → `LITERAL`. Members of
  one enum **must be uniform**; a mixed set produces no enum and a `logger.warn`, and the
  property falls back to `String`.
- **Name source, in priority order:**
  1. `sh:class` → local-name of the class IRI. **IRI-membered enums only** — `sh:class`
     constrains values to be node instances of the class, which is valid SHACL with IRI
     `sh:in` members but contradictory with literal members. A property carrying `sh:class`
     together with a literal `sh:in` set is therefore treated as a malformed shape: no enum,
     `logger.warn`.
  2. JSON-LD type mapping whose `@type` is a class IRI — i.e. a `JsonLdType.Iri` that is
     **not** an `xsd:` datatype and not `@id` → its local-name. This is the name source for
     **literal-membered** enums (since `sh:class` cannot name them), and an alternative source
     for IRI-membered enums.
  3. None → not an enum; the property stays `String` and keeps the literal `sh:in`
     membership validation already shipped in the wrapper's `validate()`.
- **Consequence:** `sh:class`-named enums are always IRI-membered; literal-membered enums are
  always named via a JSON-LD class-IRI `@type`. An enum with no valid name source for its
  member kind is not generated (stays `String`).
- **Constant names:** IRI local-name, or sanitized literal lexical, upcased to `UPPER_SNAKE`.
  Within-enum collisions get a numeric suffix (`VALUE`, `VALUE_2`).
- **Enum-name collisions across the package:** identical name + identical member set → a single
  shared enum; identical name + different members → `logger.warn` and the second is suffixed.

This requires enhancing the SHACL parsing to record each `sh:in` member's kind (today they are
flattened to strings). The new kind lives on `EnumMember`; `ShaclProperty.inValues` is left
unchanged so the existing literal-membership validation is unaffected.

## 4. Integration points

- **Model:** `OntologyModel` gains `val enums: List<EnumModel> = emptyList()`. `ShaclProperty`
  gains `val enumName: String? = null`, populated by the extractor (default `null` → no impact
  on non-enum code paths).
- **`TypeMapper`:** when `property.enumName != null`, the mapped type is the sealed-interface
  `ClassName`, with the usual cardinality rules applied (`T` / `T?` / `List<T>`).
- **`EnumGenerator` (new):** emits one `FileSpec` per `EnumModel` into the target package;
  wired into the output through `GenerationCoordinator`.
- **Read path** (wrapper, data-class factory, DSL): convert each value with
  `EnumName.from(<iri|code>)`. IRI enums read object IRIs (`getObjectValues`); literal enums
  read literal lexical values (`getLiteralValues`).
- **Write path** (`toTriples` / `writeToGraph`): emit `value.iri` as an IRI object, or
  `value.code` as a typed literal (using the member/enum `datatype`). `Unknown` round-trips
  losslessly.
- **OntoMapper / materialization:** enums are value conversions, **not** `RdfBacked` types — no
  registry entry; the conversion is inline in the generated property initializer.

## 5. Validation interaction

The type system enforces "Known or Unknown"; the embedded `validate()` still *reports*
out-of-set values as `sh:in` violations (an `Unknown` corresponds to a violation), so
type-safety and SHACL reporting stay complementary. The `sh:in` membership check shipped in
`OntologyWrapperGenerator.generateEmbeddedValidation` is currently literal-only; this work adds
the IRI-membered case (compare object IRIs against the set), using the same `ShaclViolation`
pattern.

## 6. Testing (TDD)

Every unit is built test-first, following the existing string-assertion convention plus the
compile-check approach established for the embedded-validation work.

- **`ShaclEnumExtractor`:** `sh:class` + IRI `sh:in` → IRI `EnumModel`; literal `sh:in` + JSON-LD
  name → literal `EnumModel`; `sh:in` with no name source → no enum; mixed member kinds → no
  enum + warn; within-enum and cross-enum name collisions.
- **`EnumGenerator`:** sealed-interface shape, `Known` entries carrying `iri`/`code`, `Unknown`,
  `from` companion, for both member kinds.
- **`TypeMapper`:** enum property → sealed-interface type with each cardinality.
- **Wrapper read/write:** `from(...)` on read; `.iri`/`.code` on write; `Unknown` round-trip.
- **Validation:** IRI-membered `sh:in` violation reported by `validate()`.
- **Compile-check:** extend the existing `EmbeddedValidationCompileCheck` approach — generate an
  enum + a wrapper that uses it, and confirm the generated code type-checks on Java 21.

## 7. Future work (out of scope for v1)

- `OwlEnumExtractor`: derive `EnumModel`s from `prism:entityKind prism:Enumeration` classes and
  their `owl:NamedIndividual` members, with `rdfs:range` as the name source — full PRISM §5.4
  fidelity, reusing the v1 generators unchanged.
- Carry literal `datatype` through to a typed Kotlin value rather than `String`.
