#!/usr/bin/env bash
set -euo pipefail

# UC01/UC02 browser smoke test.
#
# Prerequisites:
#   docker compose up -d --build
#   npx (installed with Node.js/npm)
#
# Usage:
#   ./scripts/test-uc01-uc02-ui.sh
#   UI_URL=http://localhost:5173 SIDECAR_URL=http://localhost:9000 \
#     ./scripts/test-uc01-uc02-ui.sh
#
# The script dispatches SIM-01 through the canonical sidecar. Intake remains the
# real customer-journey path; no module-only test endpoint is used.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ui_url="${UI_URL:-http://localhost:5173}"
backend_url="${BACKEND_URL:-http://localhost:8080}"
sidecar_url="${SIDECAR_URL:-http://localhost:9000}"
scenario_id="${SCENARIO_ID:-SIM-01}"
codex_root="${CODEX_HOME:-${HOME}/.codex}"
pwcli="${PWCLI_PATH:-${codex_root}/skills/playwright/scripts/playwright_cli.sh}"
session_name="neo09-uc01-uc02"
artifact_dir="${repo_root}/output/playwright"
export PLAYWRIGHT_MCP_OUTPUT_DIR="${artifact_dir}"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$*"
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v npx >/dev/null 2>&1 || fail "npx is required (install Node.js/npm)"
command -v rg >/dev/null 2>&1 || fail "rg (ripgrep) is required"
command -v shasum >/dev/null 2>&1 || fail "shasum is required"
[[ -x "${pwcli}" ]] || fail "Playwright CLI wrapper not found: ${pwcli}"

mkdir -p "${artifact_dir}"

curl -fsS "${backend_url}/health" >/dev/null \
  || fail "backend is not healthy at ${backend_url}"
curl -fsS "${ui_url}/" >/dev/null \
  || fail "UI is not reachable at ${ui_url}"
curl -fsS "${sidecar_url}/health" >/dev/null \
  || fail "sidecar is not healthy at ${sidecar_url}"
pass "local services are reachable"

dispatch_response="$(
  curl -fsS -X POST "${sidecar_url}/api/v1/dispatch" \
    -H 'Content-Type: application/json' \
    -d "{\"scenarioId\":\"${scenario_id}\"}"
)"
printf '%s\n' "${dispatch_response}" | rg -q '"scenarioId"[[:space:]]*:[[:space:]]*"'"${scenario_id}"'"' \
  || fail "sidecar did not acknowledge ${scenario_id}: ${dispatch_response}"
pass "${scenario_id} dispatched through the sidecar"

case_visible=false
for _attempt in {1..20}; do
  search_response="$(
    curl -fsS --get "${backend_url}/api/v1/support/cases" \
      --data-urlencode "q=${scenario_id}" \
      --data-urlencode "limit=10"
  )"
  if printf '%s\n' "${search_response}" | rg -q '"applicationId"[[:space:]]*:[[:space:]]*"'"${scenario_id}"'"'; then
    case_visible=true
    break
  fi
  sleep 1
done
[[ "${case_visible}" == true ]] \
  || fail "${scenario_id} did not become visible through the case search API"
pass "case intake is visible to UC01"

pw() {
  "${pwcli}" --session "${session_name}" "$@"
}

checked_pw() {
  local output
  output="$(pw "$@" 2>&1)"
  if printf '%s\n' "${output}" | rg -q '### Error'; then
    fail "Playwright command failed: $*\n${output}"
  fi
}

run_code() {
  checked_pw run-code "async page => { $1; }"
}

cleanup() {
  pw close >/dev/null 2>&1 || true
}
trap cleanup EXIT

checked_pw open "${ui_url}"
checked_pw resize 1440 1000

run_code \
  "await page.getByRole('heading', { name: 'Support cases' }).waitFor({ timeout: 15000 })"
run_code \
  "await page.getByText('${scenario_id}', { exact: true }).first().waitFor({ timeout: 15000 })"
checked_pw screenshot --filename "${artifact_dir}/uc01-queue.png" --full-page
pass "UC01 queue renders ${scenario_id}"

# Exercise the keyboard path added to DataTable rather than relying only on a
# mouse click.
run_code \
  "await page.getByText('${scenario_id}', { exact: true }).first().locator('xpath=ancestor::tr').press('Enter')"
run_code \
  "await page.getByRole('heading', { name: /case-/ }).waitFor({ timeout: 10000 })"
run_code \
  "await page.getByText('CASE_OPENED', { exact: true }).waitFor({ timeout: 10000 })"
run_code \
  "await page.getByText(/Applicant details are temporarily unavailable|application not found/).waitFor({ timeout: 10000 })"
checked_pw screenshot --filename "${artifact_dir}/uc02-detail.png" --full-page
pass "UC02 detail, timeline and applicant degradation render correctly"

run_code \
  "await page.getByRole('button', { name: 'Back to board' }).click()"
run_code \
  "await page.getByRole('searchbox', { name: 'Search support cases' }).fill('${scenario_id}')"
run_code \
  "await page.getByText('${scenario_id}', { exact: true }).first().waitFor({ timeout: 10000 })"
checked_pw screenshot --filename "${artifact_dir}/uc01-search.png" --full-page
pass "UC01 application ID search returns ${scenario_id}"

for screenshot in uc01-queue.png uc02-detail.png uc01-search.png; do
  [[ -s "${artifact_dir}/${screenshot}" ]] \
    || fail "screenshot was not created: ${artifact_dir}/${screenshot}"
done
unique_screenshots="$(
  shasum -a 256 \
    "${artifact_dir}/uc01-queue.png" \
    "${artifact_dir}/uc02-detail.png" \
    "${artifact_dir}/uc01-search.png" \
    | awk '{print $1}' | sort -u | wc -l | tr -d ' '
)"
[[ "${unique_screenshots}" == "3" ]] \
  || fail "screenshots are identical; expected three distinct UI states"

printf '\nUC01/UC02 UI smoke test passed.\n'
printf 'Screenshots:\n'
printf '  %s\n' \
  "${artifact_dir}/uc01-queue.png" \
  "${artifact_dir}/uc02-detail.png" \
  "${artifact_dir}/uc01-search.png"
