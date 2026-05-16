# PySHACL (ERA-style timings)

Uses [PySHACL](https://github.com/RDFLib/pySHACL) with **rdflib** parse timing aligned loosely with the ERA Jena split:

- **Load time:** parse **data** Turtle into an `rdflib.Graph`
- **Validation time:** `pyshacl.validate(data_graph=..., shacl_graph=...)` after shapes are parsed (shapes parse is outside the validation timer, matching “shapes ready before validate()” in ERA’s Jena reference)

## Setup

```bash
cd benchmarks/shacl/scripts/pyshacl
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Run

```bash
python validate_era_style.py /path/to/data.ttl /path/to/shapes.ttl /path/to/report.ttl
```

Compare **ratios** on the same machine as `:benchmarks:shacl-era-cli` for Kastor; absolute times differ by language runtime.
