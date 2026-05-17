# Design: onto-quality v0.3 — LLM-generated explanations

This note defines **v0.3 only**: adding **optional, heuristic natural-language explanations** for findings that already exist in a [`QualityReport`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/QualityReport.kt) after SHACL (and optionally embedding) validation.

It does **not** define methodology-driven modeling review, hot-spot metrics, RAG packs, or reasoning-backed checks. Those remain separate tracks ([LLM-assisted ontology modeling review](llm-assisted-ontology-modeling-review.md), v0.4 reasoning).

---

## 1. Scope

### In scope (v0.3)

- **Input:** A materialized `QualityReport` (list of `QualityFinding` values tied to `ValidationViolation`, category, tier, optional pitfall metadata).
- **Output:** For each selected finding (or batch), a short **explanation bundle**: plain-language **what this means**, **why it might matter** for ontology practice, and **actionable next steps**—clearly labeled **non-entailed, LLM-generated**.
- **Runtime:** **[Koog](https://github.com/JetBrains/koog)** for all provider-specific LLM calls (API keys, models, streaming, structured output where supported). Kastor implements a **thin façade** only (prompt assembly, finding references, response validation, report merge).
- **Enablement:** LLM behavior is **off** unless the application (or CLI) loads the Koog-backed module and supplies **valid provider configuration** (e.g. API key). Without that, `QualityReport` behavior is unchanged.

### Out of scope (deferred)

| Topic | Where it belongs |
|--------|-------------------|
| New validation rules or shapes | v0.1 catalogue updates |
| Embedding enrichment | v0.2 (`SemanticEnricher`) |
| OWL / SKOS reasoning (materialization before SHACL) | [Reasoning in Kastor](reasoning-in-kastor.md) — **v0.4** |
| Modeling-review taxonomy, capsules, metrics-first prioritization, RAG corpus | [llm-assisted-ontology-modeling-review.md](llm-assisted-ontology-modeling-review.md) |
| Synthetic SHACL violations from the LLM | Explicit non-goal |

---

## 2. Goals and constraints

### Goals

1. **Improve human interpretability** of SHACL-derived findings (especially for curators less familiar with a given pitfall code).
2. **Keep a single report story**: consumers still start from `QualityChecker.check()`; explanations are an **optional enrichment** step.
3. **Preserve CI semantics**: explanations **must not** flip `conforms` or add machine-enforceable violations; they are **annotations**.
4. **Provider flexibility** without maintaining duplicate HTTP stacks—**Koog only** for v0.3 LLM I/O.

### Constraints

- **Heuristic only:** Every explanation carries `source = LLM` and a disclaimer that it is **not** entailment.
- **Secrets:** No API keys in repo; configuration via environment variables and/or JVM system properties aligned with Koog’s provider setup.
- **Cost / latency:** Support **batching**, **max findings per run**, and **severity filtering** (e.g. explain violations first).
- **Deterministic linking:** Each explanation must reference a **stable finding key** so markdown/JSON output is reproducible given the same report + model parameters (see §5).

---

## 3. User-facing behavior

### Library (Kotlin)

```text
val report: QualityReport = checker.check(graph)

val enricher = QualityExplanationEnricher.createOrNull(/* Koog + provider config */)
  ?: return report  // no LLM: unchanged workflow

val explained = enricher.enrich(
  report = report,
  options = ExplanationOptions(
    maxFindings = 50,
    severityAtLeast = ViolationSeverity.WARNING,
    model = "…",
  ),
)

explained.explanationsByFindingId // or merged describeMarkdown()
```

Exact naming is illustrative; final API lives in `onto-quality` (or optional `onto-quality-llm-koog` module) with `createOrNull` returning null when Koog is absent or disabled.

### CLI (`onto-qa`)

- **`explain`** (or **`check --explain`**): run `check`, then optionally call the enricher when `KASTOR_ONTO_QUALITY_LLM` (name TBD) is true and credentials exist.
- **`--explain-max N`**, **`--explain-severity …`**: caps.
- **`--explain-dry-run`**: print prompt size / finding count without calling the model.

---

## 4. Data model

### 4.1 Finding reference (`FindingRef`)

Stable key for joining explanations to rows, independent of list index:

- Preferred: **hash** (e.g. SHA-256 of canonical UTF-8 string) of:
  - shape focus nodes / path from `ValidationViolation` (whatever Kastor exposes consistently),
  - `shapeUri` if present,
  - normalized `message`,
  - severity,
  - optional `pitfall` id string.
- Document the canonical serialization in code so the same `QualityReport` always yields the same ids.

If a violation lacks stable focus identifiers, fall back to hash including **index in sorted findings list** and document that instability.

### 4.2 `FindingExplanation` (new)

| Field | Purpose |
|--------|----------|
| `findingRef` | Links to `FindingRef` |
| `summary` | 1–3 sentences |
| `whyItMatters` | Optional paragraph |
| `suggestedActions` | Bulleted strings (ontology editing / process) |
| `pitfallHint` | Optional echo of OOPS/N id or shape label for traceability |
| `modelId` | Provider model id |
| `providerKind` | e.g. `openai`, `anthropic` (for audit) |
| `promptRunId` | Hash of (finding refs in batch + system prompt version + model) |

### 4.3 `ExplainedQualityReport` (new)

Wrap or extend:

- `report: QualityReport` (unchanged underlying SHACL result)
- `explanations: List<FindingExplanation>` (partial list allowed if cap hit)

Convenience: `describeMarkdown(includeExplanations = true)` that appends an **LLM explanations** section grouped by category.

**Tiering:** Explanations are **metadata**; they do not introduce a new `QualityTier` value for violations. Optional: mark explanatory sections in markdown with a **source badge** (`LLM`) so dashboards never confuse them with SHACL rows.

---

## 5. Koog integration

### 5.1 Responsibility split

| Layer | Responsibility |
|--------|----------------|
| **Kastor** | Build `FindingRef`, serialize finding payloads for the prompt, enforce caps, parse/validate JSON, attach `FindingExplanation` to `ExplainedQualityReport`. |
| **Koog** | Provider selection, authentication, chat/completion, structured-output hooks, retries/timeouts as configured. |

### 5.2 Prompt structure (v0.3 minimal)

1. **System:** Role + hard rules: output **only** JSON matching the schema; do not contradict SHACL; do not invent IRIs not in the finding payload; if unsure, lower confidence and state uncertainty in `summary`.
2. **User:** JSON array of **finding capsules** (message, severity, category, pitfall code, optional focus IRIs / literals from violation detail).

No **RAG / methodology retrieval** required for v0.3 MVP; a short static **one-screen** best-practice blurb may be inlined in the system prompt (versioned string constant).

### 5.3 Model output JSON (versioned)

Example **`explanations.schemaVersion: 1`**:

```json
{
  "schemaVersion": 1,
  "items": [
    {
      "findingRef": "<hex>",
      "summary": "…",
      "whyItMatters": "…",
      "suggestedActions": ["…", "…"],
      "confidenceNote": "…"
    }
  ]
}
```

Parser validates **count** (one item per input ref in batch) and **required fields**; on parse failure, **retry once** with a correction instruction; then **fail soft** (omit explanations for that batch, log).

---

## 6. Modules and dependencies

**Preferred shape:**

- **`onto-quality`**: data types (`FindingRef`, `FindingExplanation`, `ExplainedQualityReport`), optional **interface** `QualityExplanationEnricher` with JVM default **no-op** or null factory.
- **`onto-quality-llm-koog`** (optional): Koog dependency, `DefaultQualityExplanationEnricher` / `qualityExplanationEnricher(...)` implementing `QualityExplanationEnricher` (package `com.geoknoesis.kastor.ontoquality.llm`).

Applications that want v0.3 add the second artifact; CLI fat-jar can include it behind a feature flag or separate distribution—product decision at implementation time.

**Kotlin/JDK alignment** must satisfy Koog’s published minimums when the optional module is enabled.

---

## 7. Configuration

Document **opt-in** and variables (exact names to match implementation):

| Variable | Meaning |
|----------|---------|
| `KASTOR_ONTO_QUALITY_LLM` | `true` / `false` — master switch for CLI |
| Provider secrets | Delegate to Koog / OpenAI / Anthropic / etc. conventions |

**Library / CLI model selection:** `LlmExplanationConfig` exposes optional `modelId` (raw provider string), `modelPreset` (`AUTO` plus named catalog presets), `provider`, `apiKey`, and `baseUrl` (Ollama). When `modelId` is set, it overrides `modelPreset`. The `onto-qa` flags `--llm-model` and `--llm-model-preset` mirror `modelId` and `modelPreset`.

Never read secrets from files checked into git.

---

## 8. Security and privacy

- Prefer **no logging** of full prompts or API keys; log **hashes** and counts.
- Curators may run on **sensitive** TBox text in violation messages; treat prompts as **confidential**; document that cloud providers may process according to their policies.
- Support **local inference** later via Koog-compatible providers (e.g. Ollama) without changing the v0.3 API—operational configuration only.

---

## 9. Testing

- **Unit:** Canonical `FindingRef` hashing; JSON parser with golden fixtures; **`ExplainedQualityReport.hasLlmExplanations`** and markdown/text sections (`ExplainedQualityReportTest` in `:tools:onto-quality`).
- **No-network:** Koog test double or stub `QualityExplanationEnricher` returning canned JSON.
- **Integration (OpenAI):** `OpenAiExplanationIntegrationTest` in `:tools:onto-quality-llm-koog` runs when **`OPENAI_API_KEY`** is set; asserts **`hasLlmExplanations`**, an explanation for the expected **`FindingRef`**, substantive **summary** length, and **markdown** section marker. Set **`KASTOR_SKIP_OPENAI_LLM_TESTS=1`** to skip on shared agents.

**Application code:** after `enrich`, use **`explained.hasLlmExplanations`** (or **`explained.explanations.isNotEmpty()`**) and optionally **`explained.explanationsByRef()[FindingRef.from(...)]`** to gate CI or UX.

---

## 10. Acceptance criteria (v0.3 done)

1. With LLM disabled or module absent, **behavior matches v0.2** (no new failures).
2. With LLM enabled and valid config, **`ExplainedQualityReport`** contains explanations linked by `findingRef` for up to `maxFindings`.
3. Markdown/text output clearly **separates** SHACL rows from **LLM explanations** and states **advisory** nature.
4. **`onto-qa`** can run explain path with documented flags and env.
5. No new SHACL shapes required for v0.3.

---

## 11. See also

- [Ontology quality feature](../features/ontology-quality.md)
- [How to check ontology quality](../guides/how-to-ontology-quality.md)
- [LLM-assisted ontology modeling review](llm-assisted-ontology-modeling-review.md) — broader LLM tier (post–v0.3)
- [Koog](https://github.com/JetBrains/koog)
