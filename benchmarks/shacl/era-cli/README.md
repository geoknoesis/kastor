# SHACL ERA benchmark CLI

Small **`java -jar`** / `installDist` entrypoint for [ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark) `engines/kastor` Docker integration.

## Usage

```bash
./gradlew :benchmarks:shacl-era-cli:installDist
build/modules/benchmarks/shacl/era-cli/install/shacl-era-cli/bin/shacl-era-cli data.ttl shapes.ttl report.ttl
```

Expected stdout (ERA `run_benchmark.sh` parses these two lines):

```text
Load time: 0.012
Validation time: 0.034
```

## Exit status

Same convention as `onto-qa`. Errors are a single line on stderr prefixed with `shacl-era-cli:`; paths and parser messages have control and bidi characters rendered as `\uXXXX`, and no stack trace is printed. Standard output only ever contains the timing lines.

| Status | Meaning |
|--------|---------|
| **0** | Report written. |
| **2** | The data or shapes file could not be parsed as Turtle. |
| **4** | Usage error: not exactly three arguments, or an input file that does not exist or is not a regular file. |
| **5** | Runtime error: validation failed unexpectedly, the report could not be written, or an internal error. |

## Docker (ERA-SHACL-Benchmark)

After `installDist`, from repo root:

```bash
docker build -f benchmarks/shacl/era-cli/Dockerfile.sample -t kastor-validation-experiment:latest build/modules/benchmarks/shacl/era-cli/install
```

Use the same three positional arguments as locally. See [Dockerfile.sample](Dockerfile.sample).

Full integration steps: [SHACL native engine benchmark design](../../../docs/kastor/design/shacl-native-engine-benchmark.md), Section 12.4.
