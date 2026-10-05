# Release-candidate review

## Current score - 5 October 2026: 80.0/100

The round-nine audit of `main` at `474e2f2` (seven independent reviewers, strict audit scale) scored
**80.0/100**. The previous audit (round 8) scored 79.7. An earlier 82/100 self-assessment written on
5 October was not supported by this audit and is withdrawn.

| Dimension | Weight | Score |
|---|---:|---:|
| Correctness and data integrity | 25% | 81.8 |
| Architecture and API consistency | 15% | 77.8 |
| Performance and scalability | 15% | 77.7 |
| Reliability and resource management | 15% | 80.3 |
| Tests and verification | 15% | 82.8 |
| Build and release engineering | 10% | 77 |
| Documentation and usability | 5% | about 82 |

Open items that cap the score: `main` CI has failed intermittently on flaky concurrency tests; the
concurrency-heavy code (`GraphStateCache`, Jena queued steps) is the main defect source; the evaluator core of
`NativeShaclValidator` and several other files remain very large; release and CI findings (for example the
publish gate and checksum-only dependency verification) are tracked in the audit and not all closed; the
publication identity, signing and first release are deliberately undecided, and nothing has been published.

This page records no independent signoff of a release candidate. Release decisions are governed by
[release acceptance](release-acceptance.md) and the [release checklist](../../reference/release-checklist.md).

## Historical appendix (superseded, do not quote as current)

Earlier assessments of this repository used different scales and evidence and are kept only as history:

- **September 2026 self-assessment** reported 9.4/10 (94.15/100 weighted) for a then-uncommitted candidate
  (based on `6c5c50d`), and an earlier 81.55 baseline. These figures were produced by the maintainers' own
  review, not an independent audit, and are superseded by the audit scores above.
- **3 October 2026** a whole-repository audit revised that to 9.0/10 after reproducing six correctness defects
  (RDF4J blank-node identity collisions, lost SHACL conformance decisions during report merging, and others).
  All six were fixed with permanent regression tests; no new numeric score was assigned at the time.
- The detailed logs and JSON evidence cited by those assessments lived in the git-ignored `build/review/`
  directory and are not part of the repository, so they cannot be verified from it.

The task ledger for the September candidate is in [KASTOR_RELEASE_TASKS.md](KASTOR_RELEASE_TASKS.md), with
[compatibility evidence](compatibility-review.md) and [release acceptance conditions](release-acceptance.md).
