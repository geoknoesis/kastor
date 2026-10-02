# Release checklist

This is the exact procedure for the first release from the hardened build. `release-readiness.yml` has run green on `main`; `publish.yml` has never run (nothing is published yet). Every step below must run for real before a tag is pushed. Local runs do not replace them.

Expected runtimes: CI about 45–60 minutes per OS; release readiness about 60–90 minutes per OS plus 25–40 minutes for the signing job; publish about 20 minutes plus Central Portal validation (up to 30 minutes).

## Status of repository settings (pending user action)

The workflows and scripts are committed, but the GitHub settings they depend on are **not applied yet**. A maintainer with admin rights must do these steps; nothing in CI applies them. State checked read-only on 2026-09-14:

| Setting | Current state | Required by | Step |
|---|---|---|---|
| Dependency graph | **disabled** (`GET repos/geoknoesis/kastor/dependency-graph/sbom` returns 404) | GitHub dependency review in `dependency-review.yml` (skipped with a notice while disabled) | 3.1 (optional) |
| `release` environment and its reviewers | **missing** | `publish.yml` approval gate | 3.3 |
| `release-tags` tag ruleset | **missing** | only admins may push `v*` tags | 3.3 |
| `main-protection` branch ruleset | **missing** (only the disabled "My ruleset" exists) | PRs and required checks on `main` | 3.3 |
| Pages source | **legacy** (`build_type: legacy`, branch `main` `/docs`) so every push deploys twice | `pages.yml` as the only deployment | 3.3 |
| Code security: Dependabot security updates, secret scanning + push protection, private vulnerability reporting | **disabled** (Dependabot vulnerability alerts already enabled; read-only check 2026-09-30) | `SECURITY.md` reporting channel, leaked-secret blocking, dependency fixes | 3.3 |
| Environment secrets (4) | **not set** | `publish.yml` signing and upload | 3.4 |

## 1. Land the changes through a pull request

1. Push the branch: `git push -u origin <branch>`.
2. Open a pull request to `main`. Wait for these checks to go green:
   - `build (ubuntu-latest)`, `build (windows-latest)`: `check`, build pins, conformance smoke, executed-test floor and skip allowlist. A `buildHealth` failure is advisory: it appears as a warning and in the job summary.
   - `shacl-w3c-suite`: the pinned W3C SHACL 1.2 corpus (the required suite must execute at least 150 cases).
   - `dependency-review`: OSV lockfile audit and secret scan; GitHub dependency review runs only when the Dependency graph is enabled, otherwise the step is skipped with a notice.
3. Fix failures in the PR. Do not lower floors or add allowlist entries without a reason.
4. Merge.

## 2. Run release readiness manually on `main`

1. Start it and follow the run:

   ```bash
   gh workflow run release-readiness.yml --ref main
   gh run list --workflow release-readiness.yml --limit 1          # note the run id
   gh run watch <run-id> --exit-status
   ```

2. Verify in the run:
   - `staged-consumer (ubuntu-latest)` and `staged-consumer (windows-latest)` both pass, then `local-signing` passes.
   - The *Verify executed test counts and skips* step prints the counts above the floors (overall 3,500; RDF corpus 1,700; SHACL module 285 with `Shacl12NativeConformanceTest=150`).
   - The *Inspect metadata and documentation jars* step prints `Validated metadata, required artifacts and SBOMs for N staged publications`.
   - The *Verify plugin configuration cache reuse* step passes (`Reusing configuration cache`).
3. Download and inspect the artifacts (`gh run download <run-id>`):
   - `staged-release-ubuntu-latest` / `staged-release-windows-latest`: `release-repository/` holds every expected artifact ID (`rdf-*`, `kastor-gen-*`, `onto-quality*`, `kastor-bom`, the plugin marker), unsigned, and each module's `-cyclonedx.json`/`-cyclonedx.xml` SBOM. **This exact artifact is what `publish.yml` signs and uploads for a tag.**
   - `local-signing-evidence`: `signing-verification.json` lists the verified artifacts with `"tampered_artifact_rejected": true`. Only a run on the release commit counts.
4. Run **Conformance** manually (`gh workflow run conformance.yml --ref main`) and check that the full RDF 1.2 and SHACL 1.2 floors pass.

## 3. Configure GitHub (once, maintainer with admin rights) — pending user action

Run these in this order.

1. **(Optional) enable the Dependency graph:** Settings → Code security → Dependency graph → Enable. Verify with `gh api repos/geoknoesis/kastor/dependency-graph/sbom --jq .sbom.name` (prints a name instead of `404`). With it enabled, `dependency-review` runs GitHub's dependency review and the configure script makes it a required check. Without it, the check still runs the OSV audit and secret scan but is not required.
2. **Dry run** (sends only read-only GET requests) and read the printed *Plan*:

   ```bash
   scripts/configure-github-release.sh --reviewer <your-github-login>
   ```

   Check that the plan shows:
   - required checks `build (ubuntu-latest)`, `build (windows-latest)`, `shacl-w3c-suite`, plus `dependency-review` only if the graph is enabled;
   - `bypass: repository admins (RepositoryRole 5, always)`. Without this bypass, maintainers could no longer push directly to `main`. Pass `--no-admin-bypass` only once every change goes through PRs;
   - `Pages build type: legacy -> workflow`;
   - the *Code security* block with the current state of each setting (settings already enabled are skipped).

   Flags: `--require-dependency-review` forces the check to be required, and `--no-dependency-review` never requires it. `--approvals N` requires N approving reviews (default 0).
3. **Apply:**

   ```bash
   scripts/configure-github-release.sh --reviewer <your-github-login> --apply
   ```

   It creates or updates:
   - the `release` environment with required reviewers and deployments only from `v*` tags;
   - the `release-tags` tag ruleset: only admins may create, update or delete `v*` tags;
   - the `main-protection` branch ruleset: pull requests, no force-push or deletion, the required checks above, admin bypass;
   - GitHub Pages built by GitHub Actions (`pages.yml`) instead of the legacy branch build;
   - code security: Dependabot vulnerability alerts (`PUT vulnerability-alerts`), Dependabot security updates (`PUT automated-security-fixes`), secret scanning with push protection (`PATCH` `security_and_analysis`), and private vulnerability reporting (`PUT private-vulnerability-reporting`). Pass `--no-security-features` to leave these untouched. Private repositories must enable the Dependency graph first (step 1).
4. **Secrets.** Set the four environment secrets. The script prints these commands; values are entered interactively, never as arguments:

   ```bash
   gh secret set KASTOR_SIGNING_KEY      --env release < private-key.asc
   gh secret set KASTOR_SIGNING_PASSWORD --env release
   gh secret set CENTRAL_TOKEN_USERNAME  --env release
   gh secret set CENTRAL_TOKEN_PASSWORD  --env release
   gh secret list --env release
   ```

5. **Verify:**
   - `gh api repos/geoknoesis/kastor/environments/release`;
   - `gh api repos/geoknoesis/kastor/rulesets`: `main-protection` and `release-tags` are `active`;
   - `gh api repos/geoknoesis/kastor/pages --jq .build_type` must print `workflow`;
   - `gh api repos/geoknoesis/kastor --jq .security_and_analysis` shows `secret_scanning` and `secret_scanning_push_protection` `enabled`, and `gh api repos/geoknoesis/kastor/private-vulnerability-reporting` prints `{"enabled":true}`.

   Push to `main` once. Only **Deploy documentation to GitHub Pages** should deploy; the legacy *pages build and deployment* run should no longer appear.

## 4. Tag and publish

1. Release commit (through a PR): `version=X.Y.Z` in `gradle.properties` and the dated `CHANGELOG.md` section. Tags always have the `v` prefix; the historical `0.2.1` tag predates this convention and is not reused.
2. After merge: `git tag -s vX.Y.Z <merge-commit> && git push origin vX.Y.Z`.
3. **Publish release** runs release readiness in the same workflow run, then waits for approval on the `release` environment. Approve it.
4. The publish job then runs these steps in order:
   - It fails fast on blank secrets.
   - It refuses a version already on Maven Central.
   - It refuses to upload while an earlier deployment of the same tag is still live (see below).
   - It does **not** rebuild. It downloads the `staged-release-ubuntu-latest` artifact that readiness staged, tested and consumed in this run, and verifies its checksums and metadata.
   - It signs every file with the release key and verifies each signature, then zips the bundle. The bundle's SHA-256 is written to the job summary.
   - It records a GitHub build provenance attestation (`actions/attest-build-provenance`) for every jar, POM, Gradle module file, CycloneDX SBOM and the bundle. Check one with `gh attestation verify <file> --repo geoknoesis/kastor`.
   - It uploads the bundle and records the deployment id as the artifact `central-deployment-vX.Y.Z` (kept 90 days).
   - It polls the Central Portal. It fails with the portal's error details if validation fails, and succeeds at `VALIDATED`.
5. Release the validated deployment at <https://central.sonatype.com/publishing/deployments>.
6. Bump `main` to the next `-SNAPSHOT`.

### Re-running a tag and dropping a deployment

The Central Portal Publisher API has no endpoint to list deployments or find one by name. `GET /api/v1/publisher/published` only reports components that are already `PUBLISHED`. Before uploading, `publish.yml` therefore queries the status of every deployment id recorded in `central-deployment-<tag>` artifacts. Any recorded deployment that is `PENDING`, `VALIDATING`, `VALIDATED`, `PUBLISHING` or `PUBLISHED` blocks the upload. `FAILED` or dropped (404) deployments do not.

To re-upload a tag whose deployment is still `VALIDATED` (for example after a wrong approval):

1. Find the id: the *Upload bundle* step log or job summary, or the `central-deployment-vX.Y.Z` artifact.
2. Drop it: <https://central.sonatype.com/publishing/deployments> → the deployment → **Drop**. The API equivalent is `curl -X DELETE -H "Authorization: Bearer <base64 user:token>" https://central.sonatype.com/api/v1/publisher/deployment/<id>`; only `VALIDATED` or `FAILED` deployments can be dropped.
3. Re-run the failed publish job.

A deployment already `PUBLISHING` or `PUBLISHED` cannot be dropped: release a new version.

One gap remains. If the runner dies between the upload's HTTP response and the record step, no id is recorded. Check the portal for a `kastor-X.Y.Z` deployment before re-running a job that died in *Upload bundle*.

## If something fails

| Symptom | Action |
|---|---|
| `Blank or missing secrets in the 'release' environment` | Set the secrets (step 3.4); re-run the job. |
| `already published on Maven Central` | Versions are immutable. Release a new version. |
| `already has live Central Portal deployments` | Release it in the portal, or drop it (see above) and re-run. |
| `staged-release-ubuntu-latest has no release-repository` / checksum mismatch | Readiness did not stage in this run; re-run the whole workflow, not only the publish job. |
| Central deployment `FAILED` | Read the printed errors, drop the deployment in the portal, fix, and release a new patch version if the tag cannot be reused. |
| `Required suite missing, too small or skipped` | The W3C corpus was not fetched or the harness fell back to the bundled fixture. Check `scripts/fetch-conformance-data.py` output. |
| Dependabot Gradle PR fails on locks or verification metadata | Run `scripts/refresh-dependency-locks.sh --commit` on the PR branch (see CONTRIBUTING). |
| `Dependency review skipped: the Dependency graph is not enabled` notice | Expected until step 3.1; not a failure. |
