#!/usr/bin/env bash
set -euo pipefail

# Guided UC01/UC02 demo. This is a presenter script, not browser automation:
# it creates cases through the real open-case contract, verifies the read APIs,
# opens the UI, and pauses with concise instructions for the person presenting.
#
# Usage:
#   ./scripts/demo-uc01-uc02.sh
#   ./scripts/demo-uc01-uc02.sh --clean       # clear local case data first
#   ./scripts/demo-uc01-uc02.sh --no-open     # do not open a browser
#   ./scripts/demo-uc01-uc02.sh --no-pause    # print all checks without stopping
#
# Overrides:
#   UI_URL=http://localhost:5173 BACKEND_URL=http://localhost:8080 \
#     ./scripts/demo-uc01-uc02.sh

ui_url="${UI_URL:-http://localhost:5173}"
backend_url="${BACKEND_URL:-http://localhost:8080}"
open_browser=true
pause_between_steps=true
clean_first=false

usage() {
  sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'
}

fail() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

section() {
  printf '\n\033[1;36m%s\033[0m\n' "$1"
}

check() {
  printf '  ✓ %s\n' "$1"
}

present() {
  printf '\n  在浏览器中演示：\n'
  printf '  %s\n' "$1"
  if [[ "${pause_between_steps}" == true ]]; then
    read -r -p "  完成后按 Enter 继续…" _
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean)
      clean_first=true
      ;;
    --no-open)
      open_browser=false
      ;;
    --no-pause)
      pause_between_steps=false
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1 (use --help)"
      ;;
  esac
  shift
done

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

section "0. 本地环境检查"
curl -fsS "${backend_url}/health" >/dev/null \
  || fail "backend is not healthy at ${backend_url}; run: docker compose up -d --build"
curl -fsS "${ui_url}/" >/dev/null \
  || fail "UI is not reachable at ${ui_url}; run: docker compose up -d --build"
check "backend ${backend_url}"
check "UI ${ui_url}"

if [[ "${clean_first}" == true ]]; then
  section "清理本模块的本地演示数据"
  "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reset-db.sh"
fi

section "1. 通过正式 open-case 接口准备演示数据"
batch_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
target_application="DEMO-${batch_id}-01"
categories=(
  CARD_NOT_ARRIVED
  COMPLAINT
  OTHER
  DATA_CORRECTION
  COMPLAINT
  APPLICATION_STATUS
  CARD_NOT_ARRIVED
  OTHER
  COMPLAINT
  AGREEMENT_QUESTION
  DATA_CORRECTION
  APPLICATION_STATUS
)

for index in "${!categories[@]}"; do
  number="$(printf '%02d' "$((index + 1))")"
  application_id="DEMO-${batch_id}-${number}"
  correlation_id="demo-uc01-uc02-${batch_id}-${number}"
  category="${categories[$index]}"
  payload="$(
    jq -n \
      --arg applicationId "${application_id}" \
      --arg correlationId "${correlation_id}" \
      --arg category "${category}" \
      --arg description "UC01/UC02 guided demo case ${number}" \
      '{
        applicationId: $applicationId,
        correlationId: $correlationId,
        command: "open-case",
        application: null,
        outputs: {},
        request: {
          category: $category,
          description: $description,
          channel: "WEB"
        }
      }'
  )"
  acknowledgement="$(
    curl -fsS -X POST "${backend_url}/api/v1/support/execute" \
      -H 'Content-Type: application/json' \
      -d "${payload}"
  )"
  [[ "$(jq -r '.status' <<<"${acknowledgement}")" == "in-progress" ]] \
    || fail "open-case was not acknowledged: ${acknowledgement}"
  printf '.'
done
printf '\n'
check "12 个 case 已通过真实 intake contract 创建（没有 UI 后门）"

target_case=""
for _attempt in {1..20}; do
  target_case="$(
    curl -fsS --get "${backend_url}/api/v1/support/cases" \
      --data-urlencode "q=${target_application}" \
      --data-urlencode "limit=10" \
      | jq -r '.[0].caseId // empty'
  )"
  [[ -n "${target_case}" ]] && break
  sleep 1
done
[[ -n "${target_case}" ]] || fail "prepared case did not become searchable"
check "详情演示 case: ${target_case}"
check "搜索演示 application: ${target_application}"

section "2. UC01 — Case Board"
empty_search="$(curl -fsS "${backend_url}/api/v1/support/cases")"
[[ "$(jq 'length' <<<"${empty_search}")" -eq 0 ]] \
  || fail "empty search should return []"
check "空搜索返回 [] / HTTP 200"

queue="$(curl -fsS "${backend_url}/api/v1/support/cases/queue")"
visible="$(jq '.cases | length' <<<"${queue}")"
total="$(jq '.totalOpen' <<<"${queue}")"
[[ "${visible}" -le 10 ]] || fail "queue returned more than 10 rows"
[[ "${total}" -ge "${visible}" ]] || fail "queue total is smaller than visible rows"
check "队列只返回 ${visible} 行，同时保留真实总数 ${total}"
printf '\n  API 当前 worst-first 顺序：\n'
jq -r '.cases[] |
  "  \(.priority // "—")  \(.category)  \(.applicationId)  breached=\(.breached)"' \
  <<<"${queue}"

if [[ "${open_browser}" == true ]] && command -v open >/dev/null 2>&1; then
  open "${ui_url}"
fi
present "查看 Open cases 的真实总数、最多 10 行、P1/P2/P3，以及表格脚注 “worst first”。"
present "在搜索框输入 ${target_application}；确认只出现 CARD_NOT_ARRIVED / P2 的目标 case。"
present "把搜索框清空；确认恢复队列。再输入 definitely-no-such-case，确认显示空状态而不是报错。"

section "3. UC02 — Case Detail & Timeline"
detail="$(curl -fsS "${backend_url}/api/v1/support/cases/${target_case}")"
[[ "$(jq -r '.category' <<<"${detail}")" == "CARD_NOT_ARRIVED" ]] \
  || fail "target case category is not CARD_NOT_ARRIVED"
[[ "$(jq -r '.priority' <<<"${detail}")" == "P2" ]] \
  || fail "target case priority is not P2"
[[ "$(jq -r '.events[0].type' <<<"${detail}")" == "CASE_OPENED" ]] \
  || fail "timeline does not start with CASE_OPENED"
[[ "$(jq -r '.events[0].actor' <<<"${detail}")" == "customer via orchestrator" ]] \
  || fail "CASE_OPENED actor is incorrect"
check "详情 API 返回 CARD_NOT_ARRIVED / P2 / ${target_application}"
check "timeline 第一条为 CASE_OPENED / customer via orchestrator"
printf '\n  第一条 timeline event：\n'
jq '.events[0]' <<<"${detail}"

present "重新搜索 ${target_application} 并点击该行；核对左侧完整 case 字段和 Timeline。"
present "核对 Timeline 从 CASE_OPENED 开始，并显示 customer via orchestrator 与客户原始描述。"

section "4. UC02 — 外部资料降级与 404"
applicant_status="$(
  curl -sS -o /dev/null -w '%{http_code}' \
    "${backend_url}/api/v1/support/cases/${target_case}/applicant"
)"
if [[ "${applicant_status}" == "200" ]]; then
  check "orchestrator 支持 application lookup；右侧应显示实时申请人资料"
else
  check "application lookup 当前返回 HTTP ${applicant_status}"
  printf '  这是当前 sidecar 不提供 v5 application-fetch GET 时的预期降级场景。\n'
  printf '  核心 case/timeline 仍来自本地数据库，右侧应显示可重试提示。\n'
fi

unknown_status="$(
  curl -sS -o /dev/null -w '%{http_code}' \
    "${backend_url}/api/v1/support/cases/case-does-not-exist"
)"
[[ "${unknown_status}" == "404" ]] \
  || fail "unknown case should be 404, got ${unknown_status}"
check "未知 caseId 返回 JSON 404，不是 500"
present "观察右侧 Live applicant：成功时显示实时资料；当前 sidecar 不支持查询时显示 warning + Retry，详情和 Timeline 不受影响。"

section "演示完成"
printf '  UI:          %s\n' "${ui_url}"
printf '  Case ID:     %s\n' "${target_case}"
printf '  Application: %s\n' "${target_application}"
printf '\n  如需干净重演：./scripts/demo-uc01-uc02.sh --clean\n'
printf '  本脚本不运行 Playwright，也不会创建截图。\n'
