import React, { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  KeyValue,
  PageHeader,
  Split,
  Spinner,
  Timeline,
} from "../design-system";
import { caseStatusTone, priorityTone, time } from "../status.js";

export default function CaseDetailScreen({
  caseId,
  onBack,
  onRetry,
  error,
  transitionError,
  transitionLoading,
  onTransition,
  supervisorError,
  supervisorLoading,
  onSupervisor,
  applicantError,
  applicantLoading,
  loading,
  detail,
  applicant,
  onViewConfig,
}) {
  const [action, setAction] = useState("PICK_UP");
  const [actor, setActor] = useState("a.khan");
  const [note, setNote] = useState("");
  const [supervisorAction, setSupervisorAction] = useState("FORCE_CLOSE");
  const [supervisorReason, setSupervisorReason] = useState("");
  const [supervisor, setSupervisor] = useState("m.reyes");
  const [supervisorAssignee, setSupervisorAssignee] = useState("");
  const [showSupervisorActions, setShowSupervisorActions] = useState(false);

  const timeline = useMemo(
    () =>
      (detail?.events ?? []).map((event) => ({
        id: `${event.type}-${event.at}`,
        title: event.type,
        detail: [event.actor, event.note].filter(Boolean).join(" · "),
        when: time(event.at),
      })),
    [detail],
  );

  const actions = useMemo(() => {
    if (!detail) return [];
    switch (detail.status) {
      case "NEW":
        return ["PICK_UP"];
      case "OPEN":
        return ["WAIT_CUSTOMER", "RESOLVE"];
      case "PENDING_CUSTOMER":
        return ["RESUME"];
      case "RESOLVED":
        return ["CLOSE", "REOPEN"];
      default:
        return [];
    }
  }, [detail]);

  const requiresNote = action === "RESOLVE";

  useEffect(() => {
    if (actions.length > 0 && !actions.includes(action)) {
      setAction(actions[0]);
    }
  }, [action, actions]);

  const submitTransition = async (event) => {
    event.preventDefault();
    if (!onTransition || !detail) return;
    await onTransition({
      action,
      actor,
      note: note.trim() ? note.trim() : null,
    });
    if (action === "RESOLVE") {
      setNote("");
    }
  };

  const submitSupervisor = async (event) => {
    event.preventDefault();
    if (!onSupervisor || !detail) return;

    if (supervisorAction === "FORCE_CLOSE") {
      const confirmed = window.confirm(
        "Force close will immediately close this case and may trigger callback. Continue?",
      );
      if (!confirmed) {
        return;
      }
    }

    await onSupervisor({
      action: supervisorAction,
      reason: supervisorReason.trim(),
      supervisor: supervisor.trim(),
      assignee:
        supervisorAction === "REASSIGN" ? supervisorAssignee.trim() : null,
    });
  };

  return (
    <>
      <PageHeader
        title={detail?.caseId ?? caseId}
        lede="case record on the left, live applicant snapshot on the right"
        meta={
          detail
            ? `${detail.category} · ${detail.priority ?? "Pricing"} · ${detail.applicationId} · v${detail.configVersion ?? "—"}`
            : undefined
        }
        actions={
          <>
            <Button
              variant="secondary"
              size="sm"
              disabled={detail?.configVersion == null}
              onClick={() => onViewConfig?.(detail.configVersion)}
            >
              View config v{detail?.configVersion ?? "—"}
            </Button>
            <Button variant="ghost" size="sm" onClick={onBack}>
              Back to board
            </Button>
          </>
        }
      />

      {error && (
        <Alert tone="negative" title="Could not load case">
          {error}
        </Alert>
      )}

      {loading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner label="Loading case detail" />
          <span>Loading case detail…</span>
        </div>
      ) : detail ? (
        <Split
          sidebar={
            <Card>
              <h3>Live applicant</h3>
              {applicantLoading ? (
                <div className="case-board-loading" aria-live="polite">
                  <Spinner label="Loading applicant details" />
                  <span>Loading live applicant details…</span>
                </div>
              ) : applicant ? (
                <KeyValue
                  stacked
                  items={[
                    ["Name", applicant.applicant?.fullName ?? "—"],
                    ["Product", applicant.product?.productCode ?? "—"],
                    [
                      "Delivery address",
                      deliveryAddress(applicant)
                        ? formatAddress(deliveryAddress(applicant))
                        : "—",
                    ],
                    ["Channel", applicant.channel ?? "—"],
                  ]}
                />
              ) : (
                <Alert
                  tone="warning"
                  title={
                    applicantError?.includes("not found")
                      ? "application not found — link may be stale"
                      : "Applicant details are temporarily unavailable"
                  }
                >
                  <p>{applicantError ?? "Retry to refresh the sidebar."}</p>
                  <Button variant="secondary" size="sm" onClick={onRetry}>
                    Retry applicant lookup
                  </Button>
                </Alert>
              )}
            </Card>
          }
        >
          <Card>
            <KeyValue
              items={[
                [
                  "Status",
                  <Badge tone={caseStatusTone(detail.status)}>
                    {detail.status}
                  </Badge>,
                ],
                ["Category", detail.category],
                ["Channel", detail.channel],
                [
                  "Priority",
                  <Badge tone={priorityTone(detail.priority)}>
                    {detail.priority ?? "Pricing"}
                  </Badge>,
                ],
                [
                  "SLA deadline",
                  detail.slaDeadline ? time(detail.slaDeadline) : "—",
                ],
                ["Breached", detail.breached ? "Yes" : "No"],
                ["Application", detail.applicationId],
                ["Assignee", detail.assignee ?? "Unassigned"],
                ["Opened", time(detail.openedAt)],
                ["Paused", `${detail.pausedMinutes ?? 0} minutes`],
                ["Description", detail.description],
              ]}
            />

            <form className="case-transition-form" onSubmit={submitTransition}>
              <div className="case-transition-grid">
                <label>
                  Action
                  <select
                    value={action}
                    onChange={(event) => setAction(event.target.value)}
                    disabled={transitionLoading || actions.length === 0}
                  >
                    {actions.length === 0 ? (
                      <option value="">No legal actions</option>
                    ) : (
                      actions.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))
                    )}
                  </select>
                </label>

                <label>
                  Actor
                  <input
                    value={actor}
                    onChange={(event) => setActor(event.target.value)}
                    placeholder="agent id"
                    disabled={transitionLoading || actions.length === 0}
                    required
                  />
                </label>

                <label className="case-transition-note">
                  Note {requiresNote ? "(required for RESOLVE)" : "(optional)"}
                  <textarea
                    value={note}
                    onChange={(event) => setNote(event.target.value)}
                    disabled={transitionLoading || actions.length === 0}
                    required={requiresNote}
                    rows={3}
                  />
                </label>
              </div>

              {transitionError && (
                <Alert tone="negative" title="Transition failed">
                  {transitionError}
                </Alert>
              )}

              <Button
                type="submit"
                disabled={transitionLoading || actions.length === 0}
              >
                {transitionLoading ? "Applying..." : "Apply transition"}
              </Button>
            </form>

            <section className="case-supervisor-form" aria-label="Supervisor actions">
              <div className="case-supervisor-header">
                <div>
                  <h3>Supervisor actions</h3>
                  <p className="case-supervisor-meta">
                    Elevated actions are separated from day-to-day agent transitions.
                  </p>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setShowSupervisorActions((current) => !current)}
                >
                  {showSupervisorActions ? "Hide" : "Show"} supervisor actions
                </Button>
              </div>

              {showSupervisorActions && (
                <form onSubmit={submitSupervisor}>
                  <Alert tone="warning" title="Supervisor override zone">
                    Use these actions only when escalation policy requires it.
                  </Alert>

                  <div className="case-transition-grid">
                    <label>
                      Supervisor action
                      <select
                        value={supervisorAction}
                        onChange={(event) => setSupervisorAction(event.target.value)}
                        disabled={supervisorLoading || detail.status === "CLOSED"}
                      >
                        <option value="FORCE_CLOSE">FORCE_CLOSE</option>
                        <option value="REASSIGN">REASSIGN</option>
                      </select>
                    </label>

                    <label>
                      Supervisor
                      <input
                        value={supervisor}
                        onChange={(event) => setSupervisor(event.target.value)}
                        placeholder="supervisor id"
                        disabled={supervisorLoading || detail.status === "CLOSED"}
                        required
                      />
                    </label>

                    {supervisorAction === "REASSIGN" && (
                      <label>
                        Assignee
                        <input
                          value={supervisorAssignee}
                          onChange={(event) => setSupervisorAssignee(event.target.value)}
                          placeholder="new assignee"
                          disabled={supervisorLoading || detail.status === "CLOSED"}
                          required
                        />
                      </label>
                    )}

                    <label className="case-transition-note">
                      Reason (required)
                      <textarea
                        value={supervisorReason}
                        onChange={(event) => setSupervisorReason(event.target.value)}
                        disabled={supervisorLoading || detail.status === "CLOSED"}
                        required
                        rows={3}
                      />
                    </label>
                  </div>

                  {supervisorError && (
                    <Alert tone="negative" title="Supervisor action failed">
                      {supervisorError}
                    </Alert>
                  )}

                  <Button
                    type="submit"
                    disabled={supervisorLoading || detail.status === "CLOSED"}
                  >
                    {supervisorLoading ? "Applying..." : "Apply supervisor action"}
                  </Button>
                </form>
              )}
            </section>
          </Card>

          <Card>
            <h3>Timeline</h3>
            <Timeline items={timeline} />
          </Card>
        </Split>
      ) : null}
    </>
  );
}

function formatAddress(address) {
  return [
    address.line1,
    address.line2,
    `${address.city} ${address.postcode}`,
    address.country,
  ]
    .filter(Boolean)
    .join(", ");
}

function deliveryAddress(application) {
  if (application.delivery?.useCurrentAddress) {
    return application.applicant?.currentAddress;
  }
  return application.delivery?.address;
}
