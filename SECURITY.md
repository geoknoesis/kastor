# Security policy

## Supported versions

Security updates are applied to the **latest minor release on `main`** and, when practical, backported to the most recent **published** release line. Kastor is currently **0.x**; APIs and behavior may change between minors—see [CHANGELOG.md](CHANGELOG.md).

| Version line | Supported |
|--------------|-----------|
| `main` (upcoming) | Yes |
| Latest published 0.x | Best effort |
| Older 0.x tags | Not guaranteed |

## Reporting a vulnerability

**Please do not** file a public GitHub issue for undisclosed security vulnerabilities.

Preferred channels (pick one):

1. **GitHub private reporting** (if enabled on the repository): **Security** tab → **Report a vulnerability**. This keeps details private to maintainers.
2. **Email:** [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com) with subject line starting with `[Kastor Security]`.

Include a **short description** of the issue, affected components (e.g. `rdf:core`, `kastor-gen:runtime`), and steps to reproduce if you can share them safely.

You should receive an acknowledgment within a few business days. We will coordinate disclosure and fixes with you before public release notes when possible.

## Scope

This policy covers the **Kastor** repositories and published artifacts (Maven coordinates under `com.geoknoesis.kastor`). It does **not** govern third-party engines (Apache Jena, Eclipse RDF4J, etc.); report upstream issues to those projects according to their policies.

## Safe harbor

We support responsible disclosure and will not take legal action against researchers who follow this process and make a good-faith effort to avoid privacy violations, destruction of data, or disruption of services.
