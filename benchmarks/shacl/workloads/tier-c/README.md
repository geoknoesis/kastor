# Tier C — product / real-world

Use **corpora** that are not checked into this repo by default:

- **[ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark)** — run `get_data.sh`, then point descriptors at generated `data/*.ttl` and `shapes/*.ttl` (document absolute paths locally; do not commit RINF-derived dumps without license review).
- **Ontology quality** — e.g. `tools/onto-quality` bundled shapes + `oops-corpus` (often validated as one catalog run rather than a single data/shapes file pair).

Add JSON descriptors here once paths are stable for your automation.
