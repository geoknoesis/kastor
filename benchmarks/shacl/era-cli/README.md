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

## Docker (ERA-SHACL-Benchmark)

After `installDist`, from repo root:

```bash
docker build -f benchmarks/shacl/era-cli/Dockerfile.sample -t kastor-validation-experiment:latest build/modules/benchmarks/shacl/era-cli/install
```

Use the same three positional arguments as locally. See [Dockerfile.sample](Dockerfile.sample).

Full integration steps: [SHACL native engine benchmark design](../../../docs/kastor/design/shacl-native-engine-benchmark.md), Section 12.4.
