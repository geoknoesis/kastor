#!/usr/bin/env bash
# Applies the GitHub repository settings the Kastor release process depends on.
#
#   scripts/configure-github-release.sh --reviewer <github-login> [--reviewer <login> ...]           # dry run
#   scripts/configure-github-release.sh --reviewer <github-login> --apply                            # change GitHub
#
# Options:
#   --repo OWNER/NAME     Target repository (default: the current checkout's GitHub repository).
#   --reviewer LOGIN      Required reviewer on the `release` environment (repeatable, at least one).
#   --approvals N         Approving reviews required on pull requests to main (default 0: a solo
#                         maintainer cannot approve their own PR; PRs and green CI are still required).
#   --apply               Perform the calls. Without it, every call is printed and nothing changes.
#
# What it configures (see docs/reference/release-checklist.md):
#   1. Environment `release`: required reviewers, deployments only from tags matching `v*`.
#   2. Tag ruleset `release-tags`: only repository admins may create, update or delete `v*` tags.
#   3. Branch ruleset `main-protection`: PRs required, no force-push/deletion, required CI checks.
#   4. GitHub Pages built by GitHub Actions (`pages.yml`) instead of the legacy branch build.
# Secrets are never passed through this script. It prints the `gh secret set` commands to run.
#
# Requires: gh (authenticated with admin rights on the repository), jq-free (uses gh --jq).
set -euo pipefail

apply=false
repo=""
approvals=0
reviewers=()

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) apply=true ;;
    --dry-run) apply=false ;;
    --repo) repo="$2"; shift ;;
    --reviewer) reviewers+=("$2"); shift ;;
    --approvals) approvals="$2"; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
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

# Required status checks: job names (plus matrix suffix) from .github/workflows that run on pull requests.
required_checks=(
  "build (ubuntu-latest)"
  "build (windows-latest)"
  "shacl-w3c-suite"
  "dependency-review"
)

mode="DRY RUN (pass --apply to change $repo)"
$apply && mode="APPLYING to $repo"
echo "== $mode"

# call METHOD PATH [JSON]: prints the call; performs it only with --apply. GET lookups always run.
call() {
  local method="$1" path="$2" body="${3:-}"
  echo
  echo "gh api -X $method repos/$repo/$path${body:+ --input - <<'JSON'}"
  [ -n "$body" ] && printf '%s\nJSON\n' "$body"
  if $apply; then
    if [ -n "$body" ]; then
      printf '%s' "$body" | gh api -X "$method" "repos/$repo/$path" --input - >/dev/null
    else
      gh api -X "$method" "repos/$repo/$path" >/dev/null
    fi
    echo "   -> done"
  fi
}

json_list() { # json_list a b c -> "a","b","c"
  local out="" item
  for item in "$@"; do out="$out${out:+,}\"$item\""; done
  printf '%s' "$out"
}

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
upsert_ruleset "main-protection" "$(cat <<JSON
{
  "name": "main-protection",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
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
call PUT "pages" '{ "build_type": "workflow" }'

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
- The legacy ruleset "My ruleset" (disabled) is left untouched; delete it in Settings > Rules if unused.
EOF
