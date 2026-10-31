# Semantic Lens — Real Kastor Provider Gate

This is a **minimal provider integration gate** on a feature branch, not a replacement for the full v0.23 Semantic Lens engine. It uses the real Kastor `RdfRepository`, `withSelectRows()`, and `readTransaction()` interfaces.

The two JUnit tests open actual Jena and RDF4J memory repositories. They verify streaming SELECT, IRI result decoding, node/edge graph closure, bounded consumption, timed execution and named-graph scope. Other v0.23 lens-algebra tests must be integrated after this gate is green.

Run: `./gradlew :rdf:lens:compileKotlin :rdf:lens:test --no-daemon --stacktrace`.

Caveat: SPARQL is expected to have been compiled and authorized by the lens compiler. This provider seam does not sanitize arbitrary query text, enforce source authorization, or replace provider work/memory timeouts.
