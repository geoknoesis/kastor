# OOPS! Test Corpus Attribution

The Turtle files in this directory are derived from the OOPS!
(OntOlogy Pitfall Scanner!) reference test suite, originally published at:

    https://github.com/oeg-upm/OOPS

The OOPS! project is licensed under Apache License 2.0. The original
test ontologies were authored by María Poveda-Villalón, Asunción
Gómez-Pérez, Mari Carmen Suárez-Figueroa, and contributors.

Files in this directory may include light format conversions (RDF/XML
to Turtle) but preserve the original axioms used by OOPS! to test
pitfall detection. They are used here under Apache 2.0 to calibrate
Kastor's `onto-quality` module against the OOPS! reference.

Citation:

    Poveda-Villalón, M., Gómez-Pérez, A., & Suárez-Figueroa, M. C.
    (2014). OOPS! (OntOlogy Pitfall Scanner!): An on-line tool for
    ontology evaluation. International Journal on Semantic Web and
    Information Systems, 10(2), 7-34.

## Corpus layout in upstream OOPS!

As of the OOPS! revision used for this calibration:

- `run/test-pitfall.sh` passes pitfall IDs such as `P04` or `P22M1` to Maven and loads inputs from `src/test/resources/data/input/<id>.owl` (see `input_dir` in that script, rooted at `src/test/resources/data`).
- Pitfall regression inputs are therefore **`*.owl` files under `src/test/resources/data/input/`**.

There is **no** `P09.owl` in that directory, and **`P09` is not listed in `valid_pitfalls`** inside `run/test-pitfall.sh`, so **P09** is not represented in this corpus.

OOPS! models pitfall **P22** with four separate inputs: `P22M1.owl` … `P22M4.owl`. They are copied here as `P22_M1.ttl` … `P22_M4.ttl`.

| File | Source path in oeg-upm/OOPS | OOPS! pitfall | Format |
|------|-----------------------------|---------------|--------|
| `P04.ttl` | `src/test/resources/data/input/P04.owl` | P04 — Creating unconnected ontology elements | Turtle (converted from RDF/XML) |
| `P06.ttl` | `src/test/resources/data/input/P06.owl` | P06 — Including cycles in a class hierarchy | Turtle (converted from RDF/XML) |
| `P08.ttl` | `src/test/resources/data/input/P08.owl` | P08 — Missing annotations (family) | Turtle (converted from RDF/XML) |
| `P11.ttl` | `src/test/resources/data/input/P11.owl` | P11 — Missing domain or range in properties | Turtle (converted from RDF/XML) |
| `P19.ttl` | `src/test/resources/data/input/P19.owl` | P19 — Defining multiple domains or ranges in properties | Turtle (converted from RDF/XML) |
| `P22_M1.ttl` | `src/test/resources/data/input/P22M1.owl` | P22 — Using different naming conventions in the ontology | Turtle (converted from RDF/XML) |
| `P22_M2.ttl` | `src/test/resources/data/input/P22M2.owl` | P22 | Turtle (converted from RDF/XML) |
| `P22_M3.ttl` | `src/test/resources/data/input/P22M3.owl` | P22 | Turtle (converted from RDF/XML) |
| `P22_M4.ttl` | `src/test/resources/data/input/P22M4.owl` | P22 | Turtle (converted from RDF/XML) |
| `P25.ttl` | `src/test/resources/data/input/P25.owl` | P25 — Defining a relationship as inverse to itself | Turtle (converted from RDF/XML) |
| `P26.ttl` | `src/test/resources/data/input/P26.owl` | P26 — Defining inverse relationships for a symmetric one | Turtle (converted from RDF/XML) |
| `P27.ttl` | `src/test/resources/data/input/P27.owl` | P27 — Defining wrong equivalent properties | Turtle (converted from RDF/XML) |
| `P34.ttl` | `src/test/resources/data/input/P34.owl` | P34 — Untyped class | Turtle (converted from RDF/XML) |
| `P35.ttl` | `src/test/resources/data/input/P35.owl` | P35 — Untyped property | Turtle (converted from RDF/XML) |
| `P03.ttl` | `src/test/resources/data/input/P03.owl` | P03 — Relationship “is” as class | Turtle (converted from RDF/XML) |
| `P13.ttl` | `src/test/resources/data/input/P13.owl` | P13 — Missing inverse (M1 fixture) | Turtle (converted from RDF/XML) |
| `P20.ttl` | `src/test/resources/data/input/P20.owl` | P20 — Misusing ontology annotations | Turtle (converted from RDF/XML) |
| `P24.ttl` | `src/test/resources/data/input/P24.owl` | P24 — Recursive definitions | Turtle (converted from RDF/XML) |
| `P33.ttl` | `src/test/resources/data/input/P33.owl` | P33 — Property chain singleton | Turtle (converted from RDF/XML) |
| `P36.ttl` | `src/test/resources/data/input/P36.owl` | P36 — Ontology URI carries `.owl` suffix | Turtle (converted from RDF/XML) |
| `P38.ttl` | `src/test/resources/data/input/P38.owl` | P38 — No `owl:Ontology` declaration | Turtle (converted from RDF/XML) |
| `P39.ttl` | `src/test/resources/data/input/P39.owl` | P39 — Anonymous ontology head | Turtle (converted from RDF/XML) |
| `P40.ttl` | `src/test/resources/data/input/P40.owl` | P40 — Namespace hijacking (FOAF hijack pattern) | Turtle (converted from RDF/XML) |
| `P41.ttl` | `src/test/resources/data/input/P41.owl` | P41 — No license metadata | Turtle (converted from RDF/XML) |
| `P02.ttl` | *(synthetic; see note below)* | P02 — Creating synonyms as classes | Turtle |
| `P12.ttl` | `src/test/resources/data/input/P12.owl` | P12 — Equivalent properties not explicitly declared (fixture pattern) | Turtle (converted from RDF/XML) |
| `P21.ttl` | `src/test/resources/data/input/P21.owl` | P21 — Using a miscellaneous class | Turtle (converted from RDF/XML) |
| `P32.ttl` | `src/test/resources/data/input/P32.owl` | P32 — Several classes with the same label | Turtle (converted from RDF/XML) |

## Semantic-tier note for `P02.ttl`

Upstream OOPS! `P02.owl` asserts `owl:equivalentClass` between two classes while using the **dissimilar** labels “Class A” and “Class B”, so it does not exercise embedding-based **synonym detection** the way this module’s semantic tier is defined.  
`P02.ttl` here is therefore a **small synthetic ontology** (labels “Car” / “Automobile”, no inter-class axioms) retained under the `P02` id so the parameterized calibration harness can reference a single pitfall code. Other `Pnn.ttl` files in this folder remain straight conversions of the OOPS RDF/XML inputs where noted.

- All listed `.ttl` files: converted from the upstream `.owl` (RDF/XML) sources using **Python 3 `rdflib`** (`Graph.parse(..., format="xml")` then `serialize(..., format="turtle")`). No manual axiom edits; SPDX headers in the original XML are not duplicated in Turtle (license and citation remain in this file).
