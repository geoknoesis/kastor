#!/usr/bin/env bash
# Applies the GitHub repository settings the Kastor release process depends on.
#
#   scripts/configure-github-release.sh --reviewer <github-login> [--reviewer <login> ...]           # dry run
#   scripts/configure-github-release.sh --reviewer <github-login> --apply                            # change GitHub
#
# Options:
#   --repo OWNER/NAME             Target repository (default: the current checkout's GitHub repository).
#   --reviewer LOGIN              Required reviewer on the `release` environment (repeatable, at least one).
#   --approvals N                 Approving reviews required on pull requests to main (default 0: a solo
#                                 maintainer cannot approve their own PR; PRs and green CI are still required).
#   --require-dependency-review   Require the `dependency-review` check even when the Dependency graph is not
#                                 detected as enabled. By default it is required only when the read-only probe
#                                 `GET repos/OWNER/NAME/dependency-graph/sbom` succeeds.
#   --no-dependency-review        Never require the `dependency-review` check.
#   --no-admin-bypass             Do not let repository admins bypass `main-protection`. By default admins
#                                 (RepositoryRole 5) may bypass it, so maintainer pushes and emergency merges
#                                 still work while everyone else goes through a pull request.
#   --no-security-features        Leave the repository's code-security settings (step 5) untouched.
#   --apply                       Perform the calls. Without it, every call is printed and nothing changes.
#
# What it configures (see docs/reference/release-checklist.md):
#   1. Environment `release`: required reviewers, deployments only from tags matching `v*`.
#   2. Tag ruleset `release-tags`: only repository admins may create, update or delete `v*` tags.
#   3. Branch ruleset `main-protection`: PRs required, no force-push/deletion, required CI checks,
#      admin bypass unless --no-admin-bypass.
#   4. GitHub Pages built by GitHub Actions (`pages.yml`) instead of the legacy branch build.
#   5. Code security (unless --no-security-features): Dependabot vulnerability alerts (on the Dependency
#      graph), Dependabot security updates, secret scanning with push protection, and private
#      vulnerability reporting (the channel SECURITY.md points reporters to). Settings already enabled
#      are skipped.
# Secrets are never passed through this script. It prints the `gh secret set` commands to run.
# In dry-run mode only read-only GET requests are sent.
#
# Requires: gh (authenticated; admin rights on the repository for --apply), no jq (uses gh --jq).
set -euo pipefail

apply=false
repo=""
approvals=0
reviewers=()
dependency_review=auto   # auto | always | never
admin_bypass=true
security_features=true

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) apply=true ;;
    --dry-run) apply=false ;;
    --repo) repo="$2"; shift ;;
    --reviewer) reviewers+=("$2"); shift ;;
    --approvals) approvals="$2"; shift ;;
    --require-dependency-review) dependency_review=always ;;
    --no-dependency-review) dependency_review=never ;;
    --no-admin-bypass) admin_bypass=false ;;
    --no-security-features) security_features=false ;;
    -h|--help) sed -n '2,35p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

command -v gh >/dev/null || { echo "gh (GitHub CLI) is required" >&2; exit 1; }
if [ -z "$repo" ]; then
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi
if [ "${#reviewers[@]}" -eq 0 ]; then
  echo "At least one --reviewer <github-login> is required for the release environment" >&2
  exit 2
fi
case "$approvals" in ''|*[!0-9]*) echo "--approvals must be a non-negative integer" >&2; exit 2 ;; esac

# --- pre-flight (read-only GETs) -------------------------------------------------------------------
# Dependency graph: the SBOM export only exists when the graph is enabled (404 otherwise).
graph_enabled=false
if gh api "repos/$repo/dependency-graph/sbom" --jq '.sbom.SPDXID' >/dev/null 2>&1; then
  graph_enabled=true
fi
case "$dependency_review" in
  always) require_dependency_review=true ;;
  never) require_dependency_review=false ;;
  *) require_dependency_review=$graph_enabled ;;
esac

# Required status checks: the check-run names GitHub reports for jobs that run on every pull request.
#   ci.yml                job `build` (matrix os)         -> "build (ubuntu-latest)", "build (windows-latest)"
#   ci.yml                job `shacl-w3c-suite`           -> "shacl-w3c-suite"
#   dependency-review.yml job `dependency-review`         -> "dependency-review"
# The `macos` job skips pull requests and must never be required.
required_checks=(
  "build (ubuntu-latest)"
  "build (windows-latest)"
  "shacl-w3c-suite"
)
$require_dependency_review && required_checks+=("dependency-review")

current_pages="$(gh api "repos/$repo/pages" --jq .build_type 2>/dev/null || echo "unavailable")"
# Code security state. `vulnerability-alerts` answers 204 when enabled and 404 when disabled.
enabled_or_not() { if "$@" >/dev/null 2>&1; then echo enabled; else echo disabled; fi; }
vulnerability_alerts="$(enabled_or_not gh api "repos/$repo/vulnerability-alerts")"
security_fixes="$(gh api "repos/$repo/automated-security-fixes" --jq 'if .enabled then "enabled" else "disabled" end' 2>/dev/null || echo "unknown")"
private_reporting="$(gh api "repos/$repo/private-vulnerability-reporting" --jq 'if .enabled then "enabled" else "disabled" end' 2>/dev/null || echo "unknown")"
secret_scanning="$(gh api "repos/$repo" --jq '.security_and_analysis.secret_scanning.status // "unknown"' 2>/dev/null || echo "unknown")"
push_protection="$(gh api "repos/$repo" --jq '.security_and_analysis.secret_scanning_push_protection.status // "unknown"' 2>/dev/null || echo "unknown")"
existing_rulesets="$(gh api "repos/$repo/rulesets" --jq '[.[] | "\(.name) (\(.enforcement))"] | join(", ")' 2>/dev/null || echo "unavailable")"

mode="DRY RUN (pass --apply to change $repo; only GET requests are sent)"
$apply && mode="APPLYING to $repo"
echo "== $mode"
echo
echo "== Plan"
echo "- Environment 'release': reviewers ${reviewers[*]}; deployments only from tags v*"
echo "- Ruleset 'release-tags': only admins create/update/delete refs/tags/v*"
echo "- Ruleset 'main-protection' on the default branch:"
echo "    pull request required (approvals: $approvals), no force-push, no deletion"
if $admin_bypass; then
  echo "    bypass: repository admins (RepositoryRole 5, always) - direct maintainer pushes keep working"
else
  echo "    bypass: NONE (--no-admin-bypass) - every change, including admins', must come through a PR"
fi
echo "    required checks: $(printf '"%s" ' "${required_checks[@]}")"
if $require_dependency_review; then
  echo "    dependency-review is required (dependency graph enabled: $graph_enabled, mode: $dependency_review)"
else
  echo "    dependency-review is NOT required (dependency graph enabled: $graph_enabled, mode: $dependency_review)"
  [ "$dependency_review" = auto ] && echo "    enable Settings > Code security > Dependency graph, then re-run to require it"
fi
echo "- Pages build type: $current_pages -> workflow"
echo "- Existing rulesets: ${existing_rulesets:-none}"
if $security_features; then
  echo "- Code security (current -> enabled):"
  echo "    Dependabot vulnerability alerts: $vulnerability_alerts"
  echo "    Dependabot security updates:     $security_fixes"
  echo "    secret scanning:                 $secret_scanning"
  echo "    secret scanning push protection: $push_protection"
  echo "    private vulnerability reporting: $private_reporting"
else
  echo "- Code security: left untouched (--no-security-features)"
fi

# call METHOD PATH [JSON]: prints the call; performs it only with --apply.
call() {
  local method="$1" path="$2" body="${3:-}"
  local url="repos/$repo${path:+/$path}"   # empty PATH addresses the repository itself
  echo
  echo "gh api -X $method $url${body:+ --input - <<'JSON'}"
  [ -n "$body" ] && printf '%s\nJSON\n' "$body"
  if $apply; then
    if [ -n "$body" ]; then
      printf '%s' "$body" | gh api -X "$method" "$url" --input - >/dev/null
    else
      gh api -X "$method" "$url" >/dev/null
    fi
    echo "   -> done"
  fi
}

echo
echo "== Calls"

# --- 1. release environment ---------------------------------------------------------------------
reviewer_json=""
for login in "${reviewers[@]}"; do
  id="$(gh api "users/$login" --jq .id)"
  reviewer_json="$reviewer_json${reviewer_json:+,}{\"type\":\"User\",\"id\":$id}"
done
call PUT "environments/release" "$(cat <<JSON
{
  "wait_timer": 0,
  "prevent_self_review": false,
  "reviewers": [$reviewer_json],
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
}
JSON
)"
# gh prints the error body on stdout for HTTP errors (e.g. 404 before the environment exists): rely on exit status.
existing_policy=""
if policies="$(gh api "repos/$repo/environments/release/deployment-branch-policies" \
    --jq '.branch_policies[] | select(.name == "v*" and .type == "tag") | .id' 2>/dev/null)"; then
  existing_policy="$policies"
fi
if [ -z "$existing_policy" ]; then
  call POST "environments/release/deployment-branch-policies" '{ "name": "v*", "type": "tag" }'
else
  echo; echo "# release environment already allows tag policy v* (id $existing_policy)"
fi

# --- 2 + 3. rulesets (created, or updated in place when a ruleset of that name exists) -----------
upsert_ruleset() {
  local name="$1" body="$2" id
  id="$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id" 2>/dev/null || true)"
  if [ -n "$id" ]; then call PUT "rulesets/$id" "$body"; else call POST "rulesets" "$body"; fi
}

# RepositoryRole 5 = Admin: maintainers can still cut release tags; nobody else can.
upsert_ruleset "release-tags" "$(cat <<'JSON'
{
  "name": "release-tags",
  "target": "tag",
  "enforcement": "active",
  "bypass_actors": [ { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" } ],
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [ { "type": "creation" }, { "type": "update" }, { "type": "deletion" } ]
}
JSON
)"

checks_json=""
for check in "${required_checks[@]}"; do
  checks_json="$checks_json${checks_json:+,}{\"context\":\"$check\"}"
done
bypass_json=""
$admin_bypass && bypass_json='{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }'
upsert_ruleset "main-protection" "$(cat <<JSON
{
  "name": "main-protection",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [$bypass_json],
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": $approvals,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [$checks_json] } }
  ]
}
JSON
)"

# --- 4. Pages from GitHub Actions ------------------------------------------------------------------
# Switching build_type to "workflow" stops the legacy "pages build and deployment" run on every push,
# so pages.yml is the only deployment.
if [ "$current_pages" = "workflow" ]; then
  echo; echo "# Pages already built by GitHub Actions"
else
  call PUT "pages" '{ "build_type": "workflow" }'
fi

# --- 5. Code security ---------------------------------------------------------------------------------
# Order matters: security updates need vulnerability alerts, which need the Dependency graph (always on for
# public repositories; private repositories enable it under Settings > Code security first).
if $security_features; then
  if [ "$vulnerability_alerts" = enabled ]; then
    echo; echo "# Dependabot vulnerability alerts already enabled"
  else
    call PUT "vulnerability-alerts"
  fi
  if [ "$security_fixes" = enabled ]; then
    echo; echo "# Dependabot security updates already enabled"
  else
    call PUT "automated-security-fixes"
  fi
  if [ "$secret_scanning" = enabled ] && [ "$push_protection" = enabled ]; then
    echo; echo "# Secret scanning and push protection already enabled"
  else
    call PATCH "" "$(cat <<'JSON'
{
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}
JSON
)"
  fi
  if [ "$private_reporting" = enabled ]; then
    echo; echo "# Private vulnerability reporting already enabled"
  else
    call PUT "private-vulnerability-reporting"
  fi
fi

# --- secrets (manual, never echoed) ----------------------------------------------------------------
cat <<EOF

== Secrets: run these yourself (values are read from the prompt or stdin, never from arguments)
gh secret set KASTOR_SIGNING_KEY      --env release --repo $repo < private-key.asc   # ASCII-armored
gh secret set KASTOR_SIGNING_PASSWORD --env release --repo $repo
gh secret set CENTRAL_TOKEN_USERNAME  --env release --repo $repo                     # Central Portal user token
gh secret set CENTRAL_TOKEN_PASSWORD  --env release --repo $repo
gh secret list --env release --repo $repo                                             # verify all four exist

== Afterwards
- Required checks only bind once each check has reported at least once on a PR.
- If this run enabled the Dependency graph or vulnerability alerts, re-run it so dependency-review becomes required.
- The legacy ruleset "My ruleset" (disabled) is left untouched; delete it in Settings > Rules if unused.
EOF
