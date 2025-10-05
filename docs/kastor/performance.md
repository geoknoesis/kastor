## Performance

- Batch writes inside a single transaction.
- Prefer `UPDATE INSERT DATA` for bulk inserts when available.
- Avoid many small HTTP round-trips with remote SPARQL; combine operations.
- Use named graphs to partition data for targeted queries.
- For RDF4J NativeStore and Jena TDB2, place stores on fast disks and avoid concurrent writers.

