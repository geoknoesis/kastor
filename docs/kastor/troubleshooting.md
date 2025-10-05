## Troubleshooting

- "Unsupported RDF format": Check the format string; use one of the supported names.
- "No provider found": Ensure the provider dependency is on the classpath; verify `RdfApiRegistry.providerIds()` lists it.
- Remote SPARQL failures: Verify endpoints, credentials, and that your server is running (e.g., Fuseki).
- Transaction appears to hang: Ensure `end()` is called after `commit()`/`rollback()`.
- Empty SELECT results: Check your data loaded into the correct (named/default) graph and that IRIs match exactly.

