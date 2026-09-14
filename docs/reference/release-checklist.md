# Release checklist

This is the exact procedure for the first release from the hardened build. None of the new workflows (`ci.yml` gates, `release-readiness.yml`, `publish.yml`) have run on GitHub yet. Every step below must run for real before a tag is pushed. Local runs do not replace them.

Expected runtimes: CI about 45–60 minutes per OS; release readiness about 60–90 minutes per OS plus 25–40 minutes for the signing job; publish about 20 minutes plus Central Portal validation (up to 30 minutes).

## 1. Land the changes through a pull request

1. Push the branch: `git push -u origin <branch>`.
2. Open a pull request to `main`. Wait for these checks to go green:
   - `build (ubuntu-latest)`, `build (windows-latest)`: `check`, conformance smoke, executed-test floor (1,450) and skip allowlist.
   - `shacl-w3c-suite`: the pinned W3C SHACL 1.2 corpus (the required suite must execute at least 140 cases).
   - `dependency-review`: OSV audit, secret scan, dependency review.
3. Fix failures in the PR. Do not lower floors or add allowlist entries without a reason.
4. Merge.

## 2. Run release readiness manually on `main`

1. Actions → **Release readiness** → *Run workflow* on `main` (or `gh workflow run release-readiness.yml --ref main`).
2. Both `staged-consumer (ubuntu-latest)` and `staged-consumer (windows-latest)` must pass, then `local-signing`.
3. Download and inspect the artifacts:
   - `staged-release-*`: `build/release-repository` holds every expected artifact ID (`rdf-*`, `kastor-gen-*`, `onto-quality*`, `kastor-bom`, the plugin marker).
   - `local-signing-evidence`: `signing-verification.json` lists the verified artifacts with `"tampered_artifact_rejected": true`. Signing evidence from before the artifact renames, plugin publication and `build-platform` changes is stale. Only a run on the release commit counts.
4. Run **Conformance** manually (`gh workflow run conformance.yml --ref main`) and check that the full RDF 1.2 and SHACL 1.2 floors pass.

## 3. Configure GitHub (once, maintainer with admin rights)

```bash
scripts/configure-github-release.sh --reviewer <your-github-login>            # dry run: prints every API call
scripts/configure-github-release.sh --reviewer <your-github-login> --apply    # applies them
```

The script creates or updates:

- the `release` environment with required reviewers and deployments only from `v*` tags;
- the `release-tags` tag ruleset: only admins may create, update or delete `v*` tags;
- the `main-protection` branch ruleset: pull requests, no force-push or deletion, and the four required checks above;
- GitHub Pages built by GitHub Actions (`pages.yml`) instead of the legacy branch build.

Then set the four environment secrets. The script prints these commands; values are entered interactively, never as arguments:

```bash
gh secret set KASTOR_SIGNING_KEY      --env release < private-key.asc
gh secret set KASTOR_SIGNING_PASSWORD --env release
gh secret set CENTRAL_TOKEN_USERNAME  --env release
gh secret set CENTRAL_TOKEN_PASSWORD  --env release
gh secret list --env release
```

Verify: `gh api repos/geoknoesis/kastor/environments/release`, `gh api repos/geoknoesis/kastor/rulesets`, and `gh api repos/geoknoesis/kastor/pages --jq .build_type` (must print `workflow`). Push to `main` once and confirm **Deploy documentation to GitHub Pages** deploys.

## 4. Tag and publish

1. Release commit (through a PR): `version=X.Y.Z` in `gradle.properties` and the dated `CHANGELOG.md` section. Tags always have the `v` prefix; the historical `0.2.1` tag predates this convention and is not reused.
2. After merge: `git tag -s vX.Y.Z <merge-commit> && git push origin vX.Y.Z`.
3. **Publish release** runs release readiness again, then waits for approval on the `release` environment. Approve it.
4. The publish job fails fast on blank secrets and refuses a version already on Maven Central. It builds and signs the bundle, uploads it, then polls the Central Portal. It fails with the portal's error details if validation fails, and succeeds at `VALIDATED`.
5. Release the validated deployment at <https://central.sonatype.com/publishing/deployments>.
6. Bump `main` to the next `-SNAPSHOT`.

## If something fails

| Symptom | Action |
|---|---|
| `Blank or missing secrets in the 'release' environment` | Set the secrets (step 3); re-run the job. |
| `already published on Maven Central` | Versions are immutable. Release a new version. |
| Central deployment `FAILED` | Read the printed errors, drop the deployment in the portal, fix, and release a new patch version if the tag cannot be reused. |
| `Required suite missing, too small or skipped` | The W3C corpus was not fetched or the harness fell back to the bundled fixture. Check `scripts/fetch-conformance-data.py` output. |
| Dependabot Gradle PR fails on locks or verification metadata | Run `scripts/refresh-dependency-locks.sh --commit` on the PR branch (see CONTRIBUTING). |
