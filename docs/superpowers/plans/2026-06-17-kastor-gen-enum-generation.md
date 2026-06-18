# kastor-gen Enum Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate type-safe Kotlin sealed enum types from SHACL `sh:in` closed value sets, behind an `EnumModel` seam an OWL source can feed later.

**Architecture:** A new `ShaclEnumExtractor` enriches the parsed `OntologyModel` with `EnumModel`s and tags enum-valued `ShaclProperty`s. `TypeMapper` maps tagged properties to the generated sealed type; a new `EnumGenerator` emits the sealed-interface files; the existing read/write generators (wrapper, data-class factory/writer, DSL) gain an enum branch. Unknown values are preserved via a `Known`/`Unknown` sealed type for round-trip safety.

**Tech Stack:** Kotlin, KSP, KotlinPoet 2.2.0, Apache Jena (parsing), JUnit 5 (`org.junit.jupiter`), Gradle.

**Design doc:** [docs/superpowers/specs/2026-06-17-kastor-gen-enum-generation-design.md](../specs/2026-06-17-kastor-gen-enum-generation-design.md)

## Global Constraints

- Module under change: `kastor-gen/processor` (Gradle path `:kastor-gen:processor`). Main sources under `kastor-gen/processor/src/main/kotlin`, tests under `kastor-gen/processor/src/test/kotlin`.
- Kotlin **explicit API mode** is on: every new top-level/member declaration MUST have an explicit visibility (`internal`/`public`/`private`). Generators and helpers are `internal`.
- Generated output MUST be deterministic: iterate collections in a sorted order (existing code uses `.sortedBy { it.path }` / `.toSortedMap()`).
- Generated types share the target `packageName`; same-package `ClassName("", name)` references are the established convention (see `TypeMapper.mapObjectProperty`).
- Test command form: `./gradlew :kastor-gen:processor:test --tests "<pattern>" --console=plain`.
- Tests are string-assertion against generated `FileSpec` output (existing convention; see `OntologyWrapperGeneratorTest`), plus a compile-check file pattern (see `EmbeddedValidationCompileCheck`).
- Commit message convention: `feat(kastor-gen): …` / `test(kastor-gen): …`. End commit messages with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.
- The runtime/vocab symbols available to generated code: `com.geoknoesis.kastor.gen.runtime.KastorGraphOps` (`getLiteralValues`, `getObjectValues(graph, subj, pred){child->…}`, `getRequiredLiteralValue`), `com.geoknoesis.kastor.rdf.{Iri, Literal, RdfTerm, RdfResource}`, `com.geoknoesis.kastor.rdf.vocab.SHACL`.

---

## File Structure

**New files:**
- `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/api/model/EnumModel.kt` — `EnumMemberKind`, `EnumMember`, `EnumModel`, `ShaclInValue`.
- `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/ShaclEnumExtractor.kt` — derives `EnumModel`s from SHACL + JSON-LD; tags properties.
- `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/EnumGenerator.kt` — emits sealed-interface `FileSpec` per `EnumModel`.

**Modified files:**
- `…/api/model/ShaclModel.kt` — add `ShaclProperty.enumName`, `ShaclProperty.inValuesTyped`; add `OntologyModel.enums`.
- `…/internal/parsers/ShaclParser.kt` — populate `inValuesTyped` (member kind) while parsing `sh:in`.
- `…/internal/utils/TypeMapper.kt` — enum branch in `toKotlinType`.
- `…/internal/utils/NamingUtils.kt` — add `toEnumConstant` (UPPER_SNAKE).
- `…/internal/codegen/OntologyWrapperGenerator.kt` — enum read branch; IRI-membered `sh:in` validation.
- `…/internal/codegen/DataClassFactoryGenerator.kt` — enum read branch.
- `…/internal/codegen/DataClassWriterGenerator.kt` — enum write branch.
- `…/internal/codegen/InstanceDslGenerator.kt` — enum setter (type via TypeMapper; value passthrough).
- `…/internal/core/GenerationCoordinator.kt` — run extractor; write enum files.

**New test files mirror each unit under** `…/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/` (and `…/parsers/` for the parser).

---

### Task 1: EnumModel types and model extensions

**Files:**
- Create: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/api/model/EnumModel.kt`
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/api/model/ShaclModel.kt`
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumModelTest.kt`

**Interfaces:**
- Produces: `EnumMemberKind { IRI, LITERAL }`; `EnumMember(constantName: String, iri: String?, code: String?, datatype: String?)`; `EnumModel(name: String, classIri: String?, memberKind: EnumMemberKind, members: List<EnumMember>)`; `ShaclInValue(value: String, isIri: Boolean, datatype: String?)`; `ShaclProperty.enumName: String?` (default null); `ShaclProperty.inValuesTyped: List<ShaclInValue>?` (default null); `OntologyModel.enums: List<EnumModel>` (default emptyList()).

- [ ] **Step 1: Write the failing test**

`EnumModelTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.EnumMember
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EnumModelTest {
    @Test
    fun `enum model carries name kind and members`() {
        val m = EnumModel(
            name = "DocumentStatus",
            classIri = "https://ex/#DocumentStatus",
            memberKind = EnumMemberKind.IRI,
            members = listOf(EnumMember(constantName = "DRAFT", iri = "https://ex/#DRAFT")),
        )
        assertEquals("DocumentStatus", m.name)
        assertEquals(EnumMemberKind.IRI, m.memberKind)
        assertEquals("DRAFT", m.members.single().constantName)
    }

    @Test
    fun `shacl property and ontology model default enum fields to empty`() {
        val p = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = null, minCount = 0, maxCount = 1,
        )
        assertNull(p.enumName)
        assertNull(p.inValuesTyped)
        val model = OntologyModel(shapes = emptyList(), context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        assertEquals(emptyList(), model.enums)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumModelTest" --console=plain`
Expected: FAIL — `EnumModel` / `enumName` / `enums` unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

Create `EnumModel.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.api.model

/** Whether an enum's members are IRIs (named individuals) or literal codes. */
internal enum class EnumMemberKind { IRI, LITERAL }

/**
 * A single enum member.
 * @param constantName Kotlin identifier (UPPER_SNAKE)
 * @param iri set when the enum's memberKind is IRI
 * @param code set when the enum's memberKind is LITERAL
 * @param datatype literal datatype IRI when kind is LITERAL and not xsd:string
 */
internal data class EnumMember(
    val constantName: String,
    val iri: String? = null,
    val code: String? = null,
    val datatype: String? = null,
)

/**
 * A generated enum type derived from a SHACL sh:in closed value set.
 * @param name Kotlin type name (PascalCase)
 * @param classIri sh:class IRI when that was the name source, else null
 */
internal data class EnumModel(
    val name: String,
    val classIri: String?,
    val memberKind: EnumMemberKind,
    val members: List<EnumMember>,
)

/** A typed sh:in member captured during parsing (preserves IRI-vs-literal kind). */
internal data class ShaclInValue(
    val value: String,
    val isIri: Boolean,
    val datatype: String? = null,
)
```

In `ShaclModel.kt`, add two fields to `ShaclProperty` (after `inValues`):
```kotlin
    // Value constraints
    val inValues: List<String>? = null,
    val inValuesTyped: List<ShaclInValue>? = null,
    val enumName: String? = null,
    val hasValue: String? = null,
```
and add `enums` to `OntologyModel`:
```kotlin
data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext,
    val enums: List<EnumModel> = emptyList(),
)
```
Note: `ShaclProperty` and `OntologyModel` are currently `public data class`. Keep their existing visibility; `EnumModel`/`EnumMember`/`ShaclInValue`/`EnumMemberKind` are `internal`. Because public `ShaclProperty`/`OntologyModel` now reference `internal` types in member positions, change those two classes to `internal data class` (they are only used inside the processor). If a compile error reports public-API exposure, that change resolves it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumModelTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full processor suite (no regressions from visibility change)**

Run: `./gradlew :kastor-gen:processor:test --console=plain`
Expected: `BUILD SUCCESSFUL`. If any test referenced `ShaclProperty`/`OntologyModel` as public from outside, fix the reference; all such tests are in the same module.

- [ ] **Step 6: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/api/model/EnumModel.kt \
        kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/api/model/ShaclModel.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumModelTest.kt
git commit -m "feat(kastor-gen): add EnumModel types and model fields for enum generation"
```

---

### Task 2: Parser captures sh:in member kind

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/parsers/ShaclParser.kt` (the `sh:in` extraction block, currently builds `inValues` only)
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/parsers/ShaclEnumParseTest.kt`

**Interfaces:**
- Consumes: `ShaclInValue` (Task 1).
- Produces: `ShaclParser` now populates `ShaclProperty.inValuesTyped` (each entry `isIri=true` for IRI members, `false` for literals; `datatype` set for typed literals) in addition to the existing `inValues`.

- [ ] **Step 1: Write the failing test**

`ShaclEnumParseTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShaclEnumParseTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `sh in IRI members are captured as typed IRI values`() {
        val ttl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://ex/#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:DocShape a sh:NodeShape ; sh:targetClass ex:Doc ;
              sh:property [ sh:path ex:status ; sh:class ex:DocumentStatus ;
                            sh:in ( ex:DRAFT ex:ACTIVE ) ] .
        """.trimIndent()
        val prop = ShaclParser(logger).parseShaclContent(ttl).single().properties.single()
        val typed = prop.inValuesTyped!!
        assertEquals(2, typed.size)
        assertTrue(typed.all { it.isIri })
        assertTrue(typed.any { it.value == "https://ex/#DRAFT" })
    }

    @Test
    fun `sh in literal members are captured as typed literal values`() {
        val ttl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://ex/#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:DocShape a sh:NodeShape ; sh:targetClass ex:Doc ;
              sh:property [ sh:path ex:code ; sh:datatype xsd:string ;
                            sh:in ( "LOW" "HIGH" ) ] .
        """.trimIndent()
        val prop = ShaclParser(logger).parseShaclContent(ttl).single().properties.single()
        val typed = prop.inValuesTyped!!
        assertEquals(2, typed.size)
        assertTrue(typed.none { it.isIri })
        assertTrue(typed.any { it.value == "LOW" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*ShaclEnumParseTest" --console=plain`
Expected: FAIL — `inValuesTyped` is null (NPE on `!!`).

- [ ] **Step 3: Write minimal implementation**

In `ShaclParser.kt`, replace the existing `sh:in` extraction block (the `val inValues = …` `while` loop walking `rdf:first`/`rdf:rest`) so it also builds a typed list. Replace:
```kotlin
            // Extract sh:in values (RDF list)
            val inValues = propertyShape.getProperty(inProp)?.resource?.let { listResource ->
                val listValues = mutableListOf<String>()
                var current: org.apache.jena.rdf.model.Resource? = listResource
                while (current != null && !current.hasProperty(model.createProperty("${RDF_NS}nil"))) {
                    val first = current.getProperty(model.createProperty("${RDF_NS}first"))
                    first?.let {
                        val value = it.string ?: it.resource?.uri
                        if (value != null) listValues.add(value)
                    }
                    current = current.getProperty(model.createProperty("${RDF_NS}rest"))?.resource
                }
                listValues.takeIf { it.isNotEmpty() }
            }
```
with:
```kotlin
            // Extract sh:in values (RDF list), preserving member kind (IRI vs literal)
            val inValuesTyped = propertyShape.getProperty(inProp)?.resource?.let { listResource ->
                val typed = mutableListOf<com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue>()
                var current: org.apache.jena.rdf.model.Resource? = listResource
                while (current != null && !current.hasProperty(model.createProperty("${RDF_NS}nil"))) {
                    val first = current.getProperty(model.createProperty("${RDF_NS}first"))
                    first?.let { stmt ->
                        val node = stmt.`object`
                        when {
                            node.isURIResource -> typed.add(
                                com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue(
                                    value = node.asResource().uri, isIri = true,
                                )
                            )
                            node.isLiteral -> typed.add(
                                com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue(
                                    value = node.asLiteral().lexicalForm, isIri = false,
                                    datatype = node.asLiteral().datatypeURI,
                                )
                            )
                        }
                    }
                    current = current.getProperty(model.createProperty("${RDF_NS}rest"))?.resource
                }
                typed.takeIf { it.isNotEmpty() }
            }
            val inValues = inValuesTyped?.map { it.value }
```
Then add `inValuesTyped = inValuesTyped,` to the `ShaclProperty(...)` constructor call (next to the existing `inValues = inValues,`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*ShaclEnumParseTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full suite (existing ShaclParserTest must still pass)**

Run: `./gradlew :kastor-gen:processor:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/parsers/ShaclParser.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/parsers/ShaclEnumParseTest.kt
git commit -m "feat(kastor-gen): capture sh:in member kind during SHACL parsing"
```

---

### Task 3: NamingUtils.toEnumConstant

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/utils/NamingUtils.kt`
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/utils/EnumNamingTest.kt`

**Interfaces:**
- Produces: `NamingUtils.toEnumConstant(raw: String): String` → UPPER_SNAKE identifier (splits on `-`, `_`, ` `, and camelCase boundaries; uppercases; prefixes `_` if it starts with a digit).

- [ ] **Step 1: Write the failing test**

`EnumNamingTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.utils

import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnumNamingTest {
    @Test fun `upper snake from simple word`() = assertEquals("DRAFT", NamingUtils.toEnumConstant("DRAFT"))
    @Test fun `upper snake from kebab`() = assertEquals("IN_PROGRESS", NamingUtils.toEnumConstant("in-progress"))
    @Test fun `upper snake from camel`() = assertEquals("IN_PROGRESS", NamingUtils.toEnumConstant("inProgress"))
    @Test fun `leading digit is prefixed`() = assertEquals("_2FA", NamingUtils.toEnumConstant("2fa"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumNamingTest" --console=plain`
Expected: FAIL — `toEnumConstant` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `NamingUtils`:
```kotlin
    /** Converts a raw value/local-name to an UPPER_SNAKE Kotlin enum constant. */
    fun toEnumConstant(raw: String): String {
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        val parts = spaced.split('-', '_', ' ', '.').filter { it.isNotBlank() }
        val joined = parts.joinToString("_") { it.uppercase() }
        val safe = joined.ifEmpty { "VALUE" }
        return if (safe.first().isDigit()) "_$safe" else safe
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumNamingTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/utils/NamingUtils.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/utils/EnumNamingTest.kt
git commit -m "feat(kastor-gen): add toEnumConstant naming helper"
```

---

### Task 4: ShaclEnumExtractor

**Files:**
- Create: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/ShaclEnumExtractor.kt`
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/ShaclEnumExtractorTest.kt`

**Interfaces:**
- Consumes: `OntologyModel`, `ShaclShape`, `ShaclProperty`, `ShaclInValue`, `JsonLdContext`, `JsonLdType`, `EnumModel`, `EnumMember`, `EnumMemberKind` (Task 1); `NamingUtils.{extractInterfaceName, toEnumConstant}` (Task 3).
- Produces: `class ShaclEnumExtractor(logger: KSPLogger)` with `fun enrich(model: OntologyModel): OntologyModel` — returns a copy where (a) `model.enums` is populated and (b) each enum-valued `ShaclProperty` has `enumName` set. Properties with no valid enum are returned unchanged.

**Rules (from design §3):**
- Candidate = property with non-empty `inValuesTyped`.
- Member kind: all `isIri` → `IRI`; all `!isIri` → `LITERAL`; mixed → skip + `logger.warn`.
- Name source: kind `IRI` and `targetClass != null` → `NamingUtils.extractInterfaceName(targetClass)`; else (either kind) a JSON-LD type mapping for the property whose `type` is `JsonLdType.Iri` with a non-`xsd:` IRI → `extractInterfaceName(thatIri)`. No source → skip (no enum).
- A `LITERAL`-kind property carrying `targetClass` (i.e. `sh:class` + literal `sh:in`) is malformed → skip + `logger.warn`.
- If the resolved name equals a `targetClass` that ALSO has its own `ShaclShape` in the model, it is a real entity, not an enum → skip + `logger.warn`.
- Dedupe by name: same name + identical member set → one `EnumModel`; same name + different members → keep first, `logger.warn`, skip the conflicting one (leave that property as non-enum).

- [ ] **Step 1: Write the failing test**

`ShaclEnumExtractorTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.rdf.Iri
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShaclEnumExtractorTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun ctx(propertyMappings: Map<String, JsonLdProperty> = emptyMap()) =
        JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = propertyMappings)

    @Test
    fun `iri members with sh class produce an IRI enum named from the class`() {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(
                ShaclInValue("https://ex/#DRAFT", isIri = true),
                ShaclInValue("https://ex/#ACTIVE", isIri = true),
            ),
        )
        val model = OntologyModel(listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx())
        val out = ShaclEnumExtractor(logger).enrich(model)
        val e = out.enums.single()
        assertEquals("DocumentStatus", e.name)
        assertEquals(EnumMemberKind.IRI, e.memberKind)
        assertEquals(setOf("DRAFT", "ACTIVE"), e.members.map { it.constantName }.toSet())
        assertEquals("DocumentStatus", out.shapes.single().properties.single().enumName)
    }

    @Test
    fun `literal members with json-ld class type produce a literal enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("LOW", isIri = false), ShaclInValue("HIGH", isIri = false)),
        )
        val context = ctx(mapOf("code" to JsonLdProperty(
            id = Iri("https://ex/#code"), type = JsonLdType.Iri(Iri("https://ex/#Priority")))))
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), context))
        val e = out.enums.single()
        assertEquals("Priority", e.name)
        assertEquals(EnumMemberKind.LITERAL, e.memberKind)
        assertEquals("LOW", e.members.first().code)
    }

    @Test
    fun `sh in without a name source yields no enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("LOW", isIri = false)),
        )
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx()))
        assertTrue(out.enums.isEmpty())
        assertNull(out.shapes.single().properties.single().enumName)
    }

    @Test
    fun `mixed member kinds yield no enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#x", name = "x", description = "",
            datatype = null, targetClass = "https://ex/#T", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("https://ex/#A", isIri = true), ShaclInValue("B", isIri = false)),
        )
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx()))
        assertTrue(out.enums.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*ShaclEnumExtractorTest" --console=plain`
Expected: FAIL — `ShaclEnumExtractor` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `ShaclEnumExtractor.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.internal.codegen.enums

import com.geoknoesis.kastor.gen.processor.api.model.EnumMember
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.google.devtools.ksp.processing.KSPLogger

private const val XSD = "http://www.w3.org/2001/XMLSchema#"

internal class ShaclEnumExtractor(private val logger: KSPLogger) {

    fun enrich(model: OntologyModel): OntologyModel {
        val classShapeIris = model.shapes.map { it.targetClass }.toSet()
        val byName = LinkedHashMap<String, EnumModel>()

        val newShapes = model.shapes.map { shape ->
            val newProps = shape.properties.map { prop -> resolve(prop, model, classShapeIris, byName) }
            shape.copy(properties = newProps)
        }
        return model.copy(shapes = newShapes, enums = byName.values.toList())
    }

    private fun resolve(
        prop: ShaclProperty,
        model: OntologyModel,
        classShapeIris: Set<String>,
        byName: LinkedHashMap<String, EnumModel>,
    ): ShaclProperty {
        val members = prop.inValuesTyped?.takeIf { it.isNotEmpty() } ?: return prop

        val allIri = members.all { it.isIri }
        val allLit = members.none { it.isIri }
        val kind = when {
            allIri -> EnumMemberKind.IRI
            allLit -> EnumMemberKind.LITERAL
            else -> { logger.warn("sh:in on ${prop.path} mixes IRI and literal members; not generating an enum"); return prop }
        }

        if (kind == EnumMemberKind.LITERAL && prop.targetClass != null) {
            logger.warn("sh:in on ${prop.path} has literal members but also sh:class; malformed, skipping enum"); return prop
        }

        val name = enumName(prop, kind) ?: return prop

        // A class that has its own NodeShape is a real entity, not an enum.
        if (prop.targetClass != null && prop.targetClass in classShapeIris) {
            logger.warn("${prop.targetClass} has its own shape; treating ${prop.path} as an object reference, not an enum"); return prop
        }

        val enumMembers = members.map {
            if (kind == EnumMemberKind.IRI)
                EnumMember(constantName = NamingUtils.toEnumConstant(localName(it.value)), iri = it.value)
            else
                EnumMember(constantName = NamingUtils.toEnumConstant(it.value), code = it.value,
                    datatype = it.datatype?.takeIf { d -> d != "${XSD}string" })
        }
        val candidate = EnumModel(name = name, classIri = prop.targetClass, memberKind = kind, members = enumMembers)

        val existing = byName[name]
        if (existing != null && existing.members.map { it.constantName }.toSet() != enumMembers.map { it.constantName }.toSet()) {
            logger.warn("enum name collision for '$name' with different members; leaving ${prop.path} as non-enum"); return prop
        }
        byName.putIfAbsent(name, candidate)
        return prop.copy(enumName = name)
    }

    private fun enumName(prop: ShaclProperty, kind: EnumMemberKind): String? {
        if (kind == EnumMemberKind.IRI && prop.targetClass != null) return NamingUtils.extractInterfaceName(prop.targetClass)
        // JSON-LD type mapping whose @type is a non-xsd class IRI
        val ctxTypeIri = (prop_jsonLdType(prop) as? JsonLdType.Iri)?.iri?.value
        if (ctxTypeIri != null && !ctxTypeIri.startsWith(XSD)) return NamingUtils.extractInterfaceName(ctxTypeIri)
        return null
    }

    private fun prop_jsonLdType(prop: ShaclProperty): JsonLdType? = currentContext?.propertyMappings?.get(prop.name)?.type

    private var currentContext: com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext? = null

    private fun localName(iri: String): String = iri.substringAfterLast('/').substringAfterLast('#')
}
```
Then wire the context: change `enrich` to set `currentContext = model.context` at its start (before mapping). Add as the first line of `enrich`:
```kotlin
        currentContext = model.context
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*ShaclEnumExtractorTest" --console=plain`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/ShaclEnumExtractor.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/ShaclEnumExtractorTest.kt
git commit -m "feat(kastor-gen): derive EnumModels from SHACL sh:in (ShaclEnumExtractor)"
```

---

### Task 5: EnumGenerator (sealed-interface FileSpec)

**Files:**
- Create: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/EnumGenerator.kt`
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumGeneratorTest.kt`

**Interfaces:**
- Consumes: `EnumModel`, `EnumMemberKind`, `OntologyModel` (Task 1).
- Produces: `class EnumGenerator(logger: KSPLogger)` with `fun generateEnums(model: OntologyModel, packageName: String): Map<String, FileSpec>` keyed by enum name.

- [ ] **Step 1: Write the failing test**

`EnumGeneratorTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumGeneratorTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun model(enum: EnumModel) = OntologyModel(
        shapes = emptyList(),
        context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
        enums = listOf(enum),
    )
    private fun render(fs: com.squareup.kotlinpoet.FileSpec) =
        java.io.StringWriter().also { fs.writeTo(it) }.toString()

    @Test
    fun `iri enum emits sealed interface with Known Unknown and from`() {
        val e = EnumModel("DocumentStatus", "https://ex/#DocumentStatus", EnumMemberKind.IRI,
            listOf(EnumMember("DRAFT", iri = "https://ex/#DRAFT"), EnumMember("ACTIVE", iri = "https://ex/#ACTIVE")))
        val code = render(EnumGenerator(logger).generateEnums(model(e), "com.example")["DocumentStatus"]!!)
        assertTrue(code.contains("sealed interface DocumentStatus"))
        assertTrue(code.contains("val iri: Iri"))
        assertTrue(code.contains("enum class Known"))
        assertTrue(code.contains("DRAFT(Iri(\"https://ex/#DRAFT\"))"))
        assertTrue(code.contains("data class Unknown"))
        assertTrue(code.contains("fun from(iri: Iri): DocumentStatus"))
    }

    @Test
    fun `literal enum emits code-based sealed interface`() {
        val e = EnumModel("Priority", null, EnumMemberKind.LITERAL,
            listOf(EnumMember("LOW", code = "LOW"), EnumMember("HIGH", code = "HIGH")))
        val code = render(EnumGenerator(logger).generateEnums(model(e), "com.example")["Priority"]!!)
        assertTrue(code.contains("sealed interface Priority"))
        assertTrue(code.contains("val code: String"))
        assertTrue(code.contains("LOW(\"LOW\")"))
        assertTrue(code.contains("fun from(code: String): Priority"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumGeneratorTest" --console=plain`
Expected: FAIL — `EnumGenerator` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `EnumGenerator.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.internal.codegen.enums

import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec

private val IRI = ClassName("com.geoknoesis.kastor.rdf", "Iri")

internal class EnumGenerator(private val logger: KSPLogger) {

    fun generateEnums(model: OntologyModel, packageName: String): Map<String, FileSpec> =
        model.enums.sortedBy { it.name }.associate { it.name to fileFor(it, packageName) }

    private fun fileFor(e: EnumModel, packageName: String): FileSpec {
        val sealedName = ClassName(packageName, e.name)
        val iriKind = e.memberKind == EnumMemberKind.IRI
        val valueProp = if (iriKind) "iri" else "code"
        val valueType = if (iriKind) "Iri" else "String"

        // enum class Known(override val <value>: <type>) : <Name> { A(..), B(..); }
        val known = TypeSpec.enumBuilder("Known").addSuperinterface(sealedName)
        known.primaryConstructor(
            com.squareup.kotlinpoet.FunSpec.constructorBuilder()
                .addParameter("value", if (iriKind) IRI else String::class)
                .build()
        )
        known.addProperty(
            com.squareup.kotlinpoet.PropertySpec.builder(valueProp, if (iriKind) IRI else String::class)
                .addModifiers(com.squareup.kotlinpoet.KModifier.OVERRIDE)
                .initializer("value").build()
        )
        e.members.forEach { m ->
            val literal = if (iriKind) "Iri(%S)" else "%S"
            val arg = if (iriKind) m.iri else m.code
            known.addEnumConstant(m.constantName,
                TypeSpec.anonymousClassBuilder().addSuperclassConstructorParameter(literal, arg).build())
        }

        val unknown = TypeSpec.classBuilder("Unknown").addModifiers(com.squareup.kotlinpoet.KModifier.DATA)
            .addSuperinterface(sealedName)
            .primaryConstructor(
                com.squareup.kotlinpoet.FunSpec.constructorBuilder()
                    .addParameter(valueProp, if (iriKind) IRI else String::class).build())
            .addProperty(
                com.squareup.kotlinpoet.PropertySpec.builder(valueProp, if (iriKind) IRI else String::class)
                    .addModifiers(com.squareup.kotlinpoet.KModifier.OVERRIDE).initializer(valueProp).build())
            .build()

        val companion = TypeSpec.companionObjectBuilder()
            .addFunction(
                com.squareup.kotlinpoet.FunSpec.builder("from")
                    .addParameter(valueProp, if (iriKind) IRI else String::class)
                    .returns(sealedName)
                    .addStatement("return Known.entries.firstOrNull { it.%L == %L } ?: Unknown(%L)", valueProp, valueProp, valueProp)
                    .build())
            .build()

        val sealed = TypeSpec.interfaceBuilder(e.name)
            .addModifiers(com.squareup.kotlinpoet.KModifier.SEALED)
            .addProperty(com.squareup.kotlinpoet.PropertySpec.builder(valueProp, if (iriKind) IRI else String::class).build())
            .addType(known.build())
            .addType(unknown)
            .addType(companion)
            .build()

        return FileSpec.builder(packageName, e.name).addType(sealed).build()
    }
}
```
Note: `valueType` is documentation-only; the actual type uses `IRI`/`String::class`. Verify the rendered output matches the test assertions (e.g. `DRAFT(Iri("https://ex/#DRAFT"))`, `fun from(iri: Iri): DocumentStatus`). Adjust KotlinPoet calls if rendering differs (e.g. enum-constant arg formatting), keeping the test as the contract.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumGeneratorTest" --console=plain`
Expected: PASS. If an assertion fails on exact rendering, adjust the generator (not the test) until the rendered tokens match.

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/enums/EnumGenerator.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumGeneratorTest.kt
git commit -m "feat(kastor-gen): generate sealed Known/Unknown enum types (EnumGenerator)"
```

---

### Task 6: TypeMapper enum branch

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/utils/TypeMapper.kt`
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/TypeMapperEnumTest.kt`

**Interfaces:**
- Consumes: `ShaclProperty.enumName` (Task 1).
- Produces: `TypeMapper.toKotlinType` returns `ClassName("", enumName)` (with cardinality) when `property.enumName != null`, taking precedence over the object/literal branches.

- [ ] **Step 1: Write the failing test**

`TypeMapperEnumTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeMapperEnumTest {
    private val ctx = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    @Test
    fun `enum property maps to the sealed type with optional cardinality`() {
        val p = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus",
        )
        assertEquals("DocumentStatus?", TypeMapper.toKotlinType(p, ctx).toString())
    }

    @Test
    fun `multi-valued enum property maps to a List`() {
        val p = ShaclProperty(
            path = "https://ex/#tags", name = "tags", description = "",
            datatype = null, targetClass = "https://ex/#Tag", minCount = 0, maxCount = null,
            enumName = "Tag",
        )
        assertEquals("kotlin.collections.List<Tag>", TypeMapper.toKotlinType(p, ctx).toString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*TypeMapperEnumTest" --console=plain`
Expected: FAIL — without the enum branch, the property with `targetClass` maps to `DocumentStatus?` via the object branch in the first test (may coincidentally pass) but the assertions pin the enum path; the multi-valued `Tag` test fails because the object branch also yields `List<Tag>` (also coincidental). To make the test genuinely drive the branch, the first test additionally asserts the enum branch is taken even when `targetClass` is null — see Step 1 note below.

Add this third case to `TypeMapperEnumTest` (so the test cannot pass via the object branch):
```kotlin
    @Test
    fun `enum property with no targetClass still maps to the sealed type`() {
        val p = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 1, maxCount = 1,
            enumName = "Priority",
        )
        assertEquals("Priority", TypeMapper.toKotlinType(p, ctx).toString())
    }
```
Re-run; the third case FAILS (maps to `String` today).

- [ ] **Step 3: Write minimal implementation**

In `TypeMapper.toKotlinType`, add the enum branch first:
```kotlin
    fun toKotlinType(
        property: ShaclProperty,
        context: JsonLdContext,
        nestedMode: NestedMode = NestedMode.INTERFACE,
        dataClassSuffix: String = "",
    ): TypeName {
        return when {
            property.enumName != null -> applyCardinality(ClassName("", property.enumName), property)
            property.targetClass != null -> mapObjectProperty(property, nestedMode, dataClassSuffix)
            else -> mapLiteralProperty(property)
        }
    }
```
`applyCardinality` is already `private`; it is in the same object, so it is callable here. `ClassName` is already imported.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*TypeMapperEnumTest" --console=plain`
Expected: PASS (all three).

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/utils/TypeMapper.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/TypeMapperEnumTest.kt
git commit -m "feat(kastor-gen): map enum-tagged properties to the sealed enum type"
```

---

### Task 7: Wrapper read path + IRI-membered sh:in validation

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/OntologyWrapperGenerator.kt` (`generatePropertyImplementation`, add `generateEnumPropertyInitializer`; extend `generateEmbeddedValidation` for IRI `sh:in`)
- Test: add cases to `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/OntologyWrapperGeneratorTest.kt`

**Interfaces:**
- Consumes: `EnumModel`/`EnumMemberKind` (Task 1), `OntologyModel.enums`, `ShaclProperty.enumName`.
- Produces: wrapper property initializers convert via `<EnumName>.from(<iri|code>)`; `validate()` reports object-IRI `sh:in` violations.

The generator must look up the enum kind by name. `generateWrappers(model, packageName)` has `model.enums`; thread `enumsByName: Map<String, EnumModel>` into `generatePropertyImplementation`.

- [ ] **Step 1: Write the failing test**

Append to `OntologyWrapperGeneratorTest`:
```kotlin
    @Test
    fun `wrapper reads an IRI enum via from`() {
        val prop = com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus",
        )
        val shape = ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))
        val enum = com.geoknoesis.kastor.gen.processor.api.model.EnumModel(
            "DocumentStatus", "https://ex/#DocumentStatus",
            com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind.IRI,
            listOf(com.geoknoesis.kastor.gen.processor.api.model.EnumMember("DRAFT", iri = "https://ex/#DRAFT")))
        val ctx = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        val model = OntologyModel(listOf(shape), ctx, enums = listOf(enum))
        val code = java.io.StringWriter().also {
            generator.generateWrappers(model, "com.example").getValue("DocWrapper").writeTo(it) }.toString()
        assertTrue(code.contains("override val status: DocumentStatus?"))
        assertTrue(code.contains("DocumentStatus.from("))
    }
```
(Reuses the test's existing `generator`, `ShaclShape`, `OntologyModel`, `JsonLdContext` imports.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*OntologyWrapperGeneratorTest" --console=plain`
Expected: FAIL — current code routes `targetClass != null` to the object initializer (`OntoMapper.materialize`), so `DocumentStatus.from(` is absent.

- [ ] **Step 3: Write minimal implementation**

In `OntologyWrapperGenerator`, thread the enum map. Where `generateWrappers` iterates shapes and builds the type (it calls `generatePropertyImplementation(property, context)`), change to pass `model.enums.associateBy { it.name }`. Update the signature:
```kotlin
    private fun generatePropertyImplementation(
        property: ShaclProperty,
        context: JsonLdContext,
        enumsByName: Map<String, EnumModel>,
    ): PropertySpec {
```
In its body, change the initializer selection:
```kotlin
        val initializer = when {
            property.enumName != null -> generateEnumPropertyInitializer(property, enumsByName.getValue(property.enumName))
            property.targetClass != null -> generateObjectPropertyInitializer(property)
            else -> generateLiteralPropertyInitializer(property)
        }
```
Add the new method:
```kotlin
    private fun generateEnumPropertyInitializer(property: ShaclProperty, enum: EnumModel): CodeBlock {
        val path = property.path
        val name = enum.name
        val single = property.maxCount == 1
        val required = property.minCount != null && property.minCount!! > 0
        return if (enum.memberKind == EnumMemberKind.IRI) {
            val base = CodeBlock.of(
                "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                "  $name.from(child as Iri)\n" +
                "}", path)
            cardinalityWrap(base, single, required, property.name)
        } else {
            val base = CodeBlock.of(
                "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { $name.from(it.lexical) }", path)
            cardinalityWrap(base, single, required, property.name)
        }
    }

    private fun cardinalityWrap(base: CodeBlock, single: Boolean, required: Boolean, propName: String): CodeBlock =
        when {
            !single -> base // List<Enum>
            required -> CodeBlock.of("%L.firstOrNull() ?: error(%S)", base, "Required enum $propName missing")
            else -> CodeBlock.of("%L.firstOrNull()", base)
        }
```
Imports: add `import com.geoknoesis.kastor.gen.processor.api.model.EnumModel` and `import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind` to the file.

Then extend `generateEmbeddedValidation` to validate IRI-membered `sh:in`. Inside the `if (property.targetClass == null)` value-constraint section there is already a literal `inValues` block. Add, OUTSIDE that `targetClass == null` guard (IRI members live on object-valued properties), a block keyed on the typed members:
```kotlin
            // IRI-membered sh:in (enum) membership check on object values
            property.inValuesTyped?.takeIf { tv -> tv.isNotEmpty() && tv.all { it.isIri } }?.let { ivs ->
                val allowed = ivs.joinToString(", ") { "Iri(\"${it.value}\")" }
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { it }.forEach { obj ->", pred)
                functionBuilder.addStatement("  if (obj !in listOf(%L)) violations.add(ShaclViolation(", allowed)
                violationTail("`in`", pred, "in violated")
                functionBuilder.addStatement("}")
            }
```
Place this inside the per-property `forEach`, after the existing literal value-constraint `if (property.targetClass == null) { … }` block.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*OntologyWrapperGeneratorTest" --console=plain`
Expected: PASS (new + existing).

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/OntologyWrapperGenerator.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/OntologyWrapperGeneratorTest.kt
git commit -m "feat(kastor-gen): wrapper reads enums via from(); validate IRI sh:in"
```

---

### Task 8: Data-class factory read + writer write for enums

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/DataClassFactoryGenerator.kt` (load path: `buildLiteralLoad`/`buildMaterializeLoad` dispatch site)
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/DataClassWriterGenerator.kt` (`buildLiteralWrite`/`buildObjectWrite` dispatch site)
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/DataClassEnumTest.kt`

**Interfaces:**
- Consumes: `ShaclProperty.enumName`, `OntologyModel.enums`, `EnumMemberKind`.
- Produces: factory converts read values via `<EnumName>.from(...)`; writer emits `value.iri` (IRI object) or `Literal(value.code)`.

The factory/writer dispatch on `property.targetClass != null` (object) vs literal. Add an `enumName != null` branch FIRST in each dispatch. Both generators have `model.enums` available in their top-level generate methods (`generateFactories(model, …)` / writer is constructed per data class); thread `enumsByName` to the load/write builder the same way as Task 7.

- [ ] **Step 1: Write the failing test**

`DataClassEnumTest.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataClassEnumTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun model(): OntologyModel {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus")
        val enum = EnumModel("DocumentStatus", "https://ex/#DocumentStatus", EnumMemberKind.IRI,
            listOf(EnumMember("DRAFT", iri = "https://ex/#DRAFT")))
        return OntologyModel(
            listOf(ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))),
            JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
            enums = listOf(enum))
    }
    private fun render(fs: com.squareup.kotlinpoet.FileSpec) = java.io.StringWriter().also { fs.writeTo(it) }.toString()

    @Test
    fun `factory converts enum read with from`() {
        val gen = DataClassFactoryGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS, writerGenerator = null)
        val code = render(gen.generateFactories(model(), "com.example").getValue("DocRecordFactory"))
        assertTrue(code.contains("DocumentStatus.from("))
    }

    @Test
    fun `writer emits enum iri`() {
        val gen = DataClassWriterGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS)
        // The writer is exercised through the factory with writeSupport; assert via the factory output.
        val factory = DataClassFactoryGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS, writerGenerator = gen)
        val code = render(factory.generateFactories(model(), "com.example").getValue("DocRecordFactory"))
        assertTrue(code.contains(".iri"))
    }
}
```
(Confirm the factory file key — `DocRecordFactory` — against existing `DataClassFactoryGeneratorTest`; adjust if the suffix/name differs.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*DataClassEnumTest" --console=plain`
Expected: FAIL — enum read currently routes through the object/materialize branch (`OntoMapper.materialize`), and the writer through object/literal — neither emits `DocumentStatus.from(` or `.iri`.

- [ ] **Step 3: Write minimal implementation**

In `DataClassFactoryGenerator`, at the load dispatch (where it chooses `buildMaterializeLoad` vs `buildLiteralLoad` based on `property.targetClass`), add a first branch when `property.enumName != null`, building:
- IRI kind, single optional: `val _status = KastorGraphOps.getObjectValues(handle.graph, handle.node, Iri("path")) { child -> DocumentStatus.from(child as Iri) }.firstOrNull()`
- IRI kind, list: drop `.firstOrNull()`.
- IRI kind, required single: `… .firstOrNull() ?: error("Required enum status missing")`.
- LITERAL kind: `KastorGraphOps.getLiteralValues(handle.graph, handle.node, Iri("path")).map { EnumName.from(it.lexical) }` with the same cardinality tail.

Mirror the `buildStringLiteralLoad` `val _name = …` shape so the surrounding assembly (which references `_name`) is unchanged. Thread `enumsByName` to the dispatch to read the member kind.

In `DataClassWriterGenerator`, at the write dispatch (object vs `buildLiteralWrite`), add a first branch when `property.enumName != null`:
- IRI kind, single required: `triples += Triple(subject, Iri("path"), record.status.iri)`
- IRI kind, single optional: `record.status?.let { triples += Triple(subject, Iri("path"), it.iri) }`
- IRI kind, list: `record.status.forEach { triples += Triple(subject, Iri("path"), it.iri) }`
- LITERAL kind: emit `Literal(it.code)` as the object instead of `it.iri`.

Use the file's existing `rdfTripleClass`, `iriClass`, `literalClass` `ClassName` fields (as in `buildLiteralWrite`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kastor-gen:processor:test --tests "*DataClassEnumTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :kastor-gen:processor:test --console=plain`
Expected: `BUILD SUCCESSFUL` (existing data-class tests unaffected).

- [ ] **Step 6: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/DataClassFactoryGenerator.kt \
        kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/DataClassWriterGenerator.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/DataClassEnumTest.kt
git commit -m "feat(kastor-gen): data-class read/write support for enums"
```

---

### Task 9: Wire extractor + EnumGenerator into the pipeline; DSL passthrough

**Files:**
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/core/GenerationCoordinator.kt`
- Modify: `kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/codegen/InstanceDslGenerator.kt` (no logic change expected — its type comes from `TypeMapper`; confirm the setter compiles for the sealed type)
- Test: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumPipelineTest.kt`

**Interfaces:**
- Consumes: `ShaclEnumExtractor` (Task 4), `EnumGenerator` (Task 5).
- Produces: `generateFromOntology` enriches the model with `ShaclEnumExtractor(...).enrich(model)` before generating, and writes `EnumGenerator(...).generateEnums(enriched, packageName)` files alongside interfaces/wrappers.

- [ ] **Step 1: Write the failing test**

`EnumPipelineTest.kt` (exercises the extractor→generator wiring at the model level, since `GenerationCoordinator` writes via KSP `CodeGenerator`):
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumPipelineTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `extractor then generator yields an enum file for a sh-class sh-in property`() {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("https://ex/#DRAFT", isIri = true)))
        val model = OntologyModel(
            listOf(ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))),
            JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        val enriched = ShaclEnumExtractor(logger).enrich(model)
        val files = EnumGenerator(logger).generateEnums(enriched, "com.example")
        assertTrue(files.containsKey("DocumentStatus"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kastor-gen:processor:test --tests "*EnumPipelineTest" --console=plain`
Expected: PASS already at the unit level (extractor + generator exist). This test guards the wiring contract; if it passes, proceed to wire the coordinator (Step 3) which has no isolated unit test (it depends on KSP `CodeGenerator`), and rely on the end-to-end compile-check in Task 10.

- [ ] **Step 3: Wire the coordinator**

In `GenerationCoordinator.generateFromOntology`, at the very top of the method body (before `interfaceGenerator`/`wrapperGenerator` use `model`), enrich and emit enums:
```kotlin
        val enriched = com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor(logger).enrich(model)
        com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator(logger)
            .generateEnums(enriched, packageName)
            .toSortedMap().forEach { (_, fileSpec) -> writeFile(fileSpec, packageName) }
```
Then replace every later use of `model` in this method with `enriched` (so `generateInterfaces`, `generateWrappers`, `generateDataClasses`, `generateFactories` all see the enum-tagged properties). Concretely: `interfaceGenerator.generateInterfaces(enriched, packageName)`, `wrapperGenerator.generateWrappers(enriched, packageName)`, `dcGenerator.generateDataClasses(enriched, packageName)`, `factoryGenerator.generateFactories(enriched, packageName)`.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew :kastor-gen:processor:test --console=plain`
Expected: `BUILD SUCCESSFUL`. The DSL generator needs no change (its type comes from `TypeMapper`); confirm no compile error in `InstanceDslGenerator`. If the DSL setter references a literal type conversion that breaks for the sealed type, gate it the same way (enum → pass the value through unchanged).

- [ ] **Step 5: Commit**

```bash
git add kastor-gen/processor/src/main/kotlin/com/geoknoesis/kastor/gen/processor/internal/core/GenerationCoordinator.kt \
        kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumPipelineTest.kt
git commit -m "feat(kastor-gen): wire enum extraction and generation into the pipeline"
```

---

### Task 10: Compile-check for generated enum code

**Files:**
- Create: `kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumCompileCheck.kt`

**Interfaces:**
- Consumes: the runtime/vocab types and the generated enum shape (Tasks 5, 7).
- Produces: a compile-only mirror of the generated sealed enum + its wrapper read/write usage, proving the generated Kotlin type-checks on Java 21 (same technique as `EmbeddedValidationCompileCheck`).

- [ ] **Step 1: Write the compile-check (this file IS the test)**

`EnumCompileCheck.kt`:
```kotlin
package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.rdf.Iri
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

// Mirror of an IRI-membered generated enum.
internal sealed interface DocumentStatusCC {
    val iri: Iri
    enum class Known(override val iri: Iri) : DocumentStatusCC {
        DRAFT(Iri("https://ex/#DRAFT")),
        ACTIVE(Iri("https://ex/#ACTIVE"));
    }
    data class Unknown(override val iri: Iri) : DocumentStatusCC
    companion object {
        fun from(iri: Iri): DocumentStatusCC =
            Known.entries.firstOrNull { it.iri == iri } ?: Unknown(iri)
    }
}

@Suppress("unused")
internal object EnumCompileCheck {
    fun read(rdf: RdfHandle): DocumentStatusCC? =
        KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri("https://ex/#status")) { child ->
            DocumentStatusCC.from(child as Iri)
        }.firstOrNull()

    fun writeIri(v: DocumentStatusCC): Iri = v.iri
}

class EnumCompileCheckTest {
    @Test
    fun `generated enum shape and usage type-check`() {
        assertNotNull(EnumCompileCheck)
    }
}
```
Confirm the `getObjectValues(graph, subj, pred) { child -> … }` mapper signature against `KastorGraphOps`; adjust the lambda if the real signature differs (the wrapper's object initializer is the reference usage).

- [ ] **Step 2: Run the full suite (this compiles the check)**

Run: `./gradlew :kastor-gen:processor:test --console=plain`
Expected: `BUILD SUCCESSFUL`. A compile error here means the generated enum shape or read usage is invalid — fix `EnumGenerator`/wrapper accordingly and re-run.

- [ ] **Step 3: Commit**

```bash
git add kastor-gen/processor/src/test/kotlin/com/geoknoesis/kastor/gen/processor/codegen/EnumCompileCheck.kt
git commit -m "test(kastor-gen): compile-check for generated enum types"
```

---

## Self-Review

**Spec coverage:**
- Hybrid `EnumModel` seam → Tasks 1, 4 (extractor is the swappable source; `EnumModel` is the boundary). ✓
- Explicit name source (sh:class / JSON-LD class-@type) → Task 4 `enumName`. ✓
- Both member kinds → Tasks 1, 2, 4, 5, 7, 8 (kind threaded through model → parser → extractor → generator → read/write). ✓
- Sealed Known/Unknown, forward-compatible + round-trip-safe → Task 5 (shape), Tasks 7–8 (read returns `Unknown`, write emits `.iri`/`.code`, preserving unknown). ✓
- Validation interaction (IRI `sh:in`) → Task 7. ✓
- Detection rules (mixed→warn, no-name→String, class-with-shape→entity, collisions) → Task 4. ✓
- Compile evidence → Task 10. ✓

**Placeholder scan:** No "TBD/TODO". Two tasks (8, 9) reference dispatch sites by method name and instruct the implementer to read the exact method before editing; the conversion code to add is given explicitly. The Task 8/9 file keys (`DocRecordFactory`) and the `getObjectValues` mapper signature are flagged for confirmation against existing tests/source.

**Type consistency:** `enumName: String?`, `inValuesTyped: List<ShaclInValue>?`, `OntologyModel.enums`, `EnumModel(name, classIri, memberKind, members)`, `EnumMember(constantName, iri, code, datatype)`, `<Enum>.from(iri|code)`, `.iri`/`.code` accessors — used consistently across Tasks 1, 4, 5, 7, 8, 10.

**Scope:** Single feature, single module (`:kastor-gen:processor`); independently testable per task.
