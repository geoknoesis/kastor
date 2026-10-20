# October quality remediation

The 19 October 2026 working-tree self-review of Kastor, based on `5c8c8280`, scored 8.4/10 after reproducing
three correctness defects. This ledger tracks the requested fixes and the engineering work toward 9.5/10.
Scores are engineering judgments. Independent review of a final candidate remains a separate requirement.

## Correctness fixes

| Finding | Result | Regression coverage |
|---|---|---|
| RDF4J adapter discarded literal direction | Unsupported directional literals fail before conversion or loading. | Both directions, shape parameters, data, nested terms, failed reloads, ordinary language/datatype distinctions. |
| Distinct findings shared a reference | Encoding v2 uses RDF kind tags and nullable, length-framed UTF-16 fields. | IRI/string distinctions, empty/absent values, delimiters, surrogates, nested keyed blank nodes, result paths and source constraints. |
| Duplicate graph insertion overwrote input | Every builder overload rejects a name already added. | Single insertion, bulk insertion, repository overload, preservation of the first graph after rejection. |

`AdapterTermParityTest` also compares term equality through native validation and both generated-code adapters,
including repeated cache loads and rolled-back atomic edits. Its 16-term matrix covers blank nodes and nested
triple terms. The RDF4J SHACL query compiler cannot handle blank-node or triple-term `sh:hasValue` parameters:
these shapes fail admission explicitly, and the matrix requires parity for admitted parameters. Native and
Jena evaluate all parameters (the matrix opts into native triple-term shape parameters). Existing deterministic cache contention,
transaction and lifecycle suites remain part of the full check.

## Regression protection

Dependency scopes declare used transitives and expose public signature types through `api`. Unused umbrella
dependencies are narrowed; parser plugins and the relocated NativeStore LZ4 implementation keep their runtime
roles. Dependency analysis must pass `scripts/check-quality-gates.py dependencies`; each remaining exception
must have a specific reason in `scripts/dependency-advice-exceptions.json`.

CI enforces module test populations, line and branch coverage, and lexical complexity growth. Native and live
coverage policies are separate from offline thresholds. `scripts/code-complexity-baseline.json` freezes existing
large declarations; its exceptions remain design debt and must not be increased without review.

The native validator delegates shapes preparation, recursion solving, property constraints, run state and report
assembly to separate components. The CLI separates streams, input, output, options, pipeline setup and LLM work
from command orchestration. Public entry points remain in place.

## Conformance and candidate evidence

The preceding pinned RDF run reported 2,117 harness tests: 1,913 passed, 64 unapproved cases were excluded, and
140 backend deviations were allowlisted. These categories must remain separate. The SHACL harness executes
166 cases, including explicit rejection tests for known unsupported features; harness success does not mean
every SHACL 1.2 feature is implemented.

The baseline for performance comparisons is pinned in `benchmarks/shacl/jmh/baselines/reference.json`.
Release readiness runs the same harness on baseline and candidate with a 15% latency/allocation budget.
The comparison runner pins both runs to the same two CPUs and records the selection; on Windows hybrid CPUs,
it selects distinct cores in the fastest [CPU efficiency class](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_cpu_set_information).
This removes hybrid-core placement as a source of variation.
The manual live LLM workflow uses one synthetic finding, a two-minute enrichment budget, one retry and
1,024 output tokens per request. Credentials are scoped to its test step.

Final acceptance requires clean Windows/Linux checks, ABI checks, staged library and plugin consumers, pinned
corpora, native lifecycle, bounded live LLM, dependency/secret checks and comparable performance evidence for
one source manifest. Verification artifacts belong under `build/review/`; a fresh independent review must
evaluate the exact final revision. Local remediation does not establish independent signoff or publication.
