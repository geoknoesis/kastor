# SHACL benchmark baselines

Checked-in numbers (or ratio bands) for regression gating should live in this directory once CI is wired.

Regeneration workflow: run `./gradlew :benchmarks:shacl:jmh` on a reference machine, record medians, and update the baseline file with the Git SHA and JVM version.

See [SHACL validation architecture](../../../../docs/kastor/design/shacl-validation-architecture.md) for threshold policy intent.
