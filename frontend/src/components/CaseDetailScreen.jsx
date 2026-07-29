import React, { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  KeyValue,
  PageHeader,
  Split,
  Spinner,
  Timeline,
} from "../design-system";
import { caseStatusTone, priorityTone, time } from "../status.js";

export default function CaseDetailScreen({
  caseId,
  backLabel,
  onBack,
  onRetry,
  error,
  transitionError,
  transitionLoading,
  onTransition,
  supervisorError,
  supervisorLoading,
  onSupervisor,
  csatError,
  csatLoading,
  onRecordCsat,
  applicantError,
  applicantLoading,
  loading,
  detail,
  applicant,
  suggestions,
  suggestionLoading,
  suggestionError,
  onViewConfig,
}) {
  const [action, setAction] = useState("PICK_UP");
  const [actor, setActor] = useState("");
  const [note, setNote] = useState("");
  const [supervisorAction, setSupervisorAction] = useState("FORCE_CLOSE");
  const [supervisorReason, setSupervisorReason] = useState("");
  const [supervisor, setSupervisor] = useState("m.reyes");
  const [supervisorAssignee, setSupervisorAssignee] = useState("");
  const [showSupervisorActions, setShowSupervisorActions] = useState(false);
  const [csatScore, setCsatScore] = useState("5");
  const [csatComment, setCsatComment] = useState("");

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
  const supervisorActions = useMemo(() => {
    if (!detail || detail.status === "CLOSED") return [];
    return ["NEW", "OPEN", "PENDING_CUSTOMER"].includes(detail.status)
      ? ["FORCE_CLOSE", "REASSIGN"]
      : ["FORCE_CLOSE"];
  }, [detail]);

  useEffect(() => {
    if (actions.length > 0 && !actions.includes(action)) {
      setAction(actions[0]);
    }
  }, [action, actions]);

  useEffect(() => {
    const nextActor = detail?.assignee?.trim();
    if (nextActor) {
      setActor(nextActor);
    }
  }, [detail?.assignee]);

  useEffect(() => {
    if (
      supervisorActions.length > 0 &&
      !supervisorActions.includes(supervisorAction)
    ) {
      setSupervisorAction(supervisorActions[0]);
    }
  }, [supervisorAction, supervisorActions]);

  const submitTransition = async (event) => {
    event.preventDefault();
    if (!onTransition || !detail) return;
    const updated = await onTransition({
      action,
      actor,
      note: note.trim() ? note.trim() : null,
    });
    if (updated && action === "RESOLVE") {
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

    const updated = await onSupervisor({
      action: supervisorAction,
      reason: supervisorReason.trim(),
      supervisor: supervisor.trim(),
      assignee:
        supervisorAction === "REASSIGN" ? supervisorAssignee.trim() : null,
    });
    if (updated) {
      setSupervisorReason("");
      setSupervisorAssignee("");
      setShowSupervisorActions(false);
    }
  };

  const submitCsat = async (event) => {
    event.preventDefault();
    if (!onRecordCsat || !detail) return;
    const recorded = await onRecordCsat({
      score: Number(csatScore),
      comment: csatComment.trim() || null,
    });
    if (recorded) setCsatComment("");
  };

  return (
    <>
      <PageHeader
        title={detail?.caseId ?? caseId}
        lede="Local case record and immutable timeline, with applicant data fetched live."
        meta={
          detail
            ? `${detail.category} · ${detail.priority ?? "Pricing"} · ${detail.applicationId} · v${detail.configVersion ?? "—"}`
            : undefined
        }
        actions={
          <>
            {detail?.configVersion != null && (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => onViewConfig?.(detail.configVersion)}
              >
                View config v{detail.configVersion}
              </Button>
            )}
            <Button variant="ghost" size="sm" onClick={onBack}>
              Back to {backLabel ?? "case board"}
            </Button>
          </>
        }
      />

      {loading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner label="Loading case detail" />
          <span>Loading case detail…</span>
        </div>
      ) : error ? (
        <EmptyState
          title={error.includes("not found") ? "Case not found" : "Case could not be loaded"}
        >
          {error.includes("not found")
            ? `No support case exists for “${caseId}”. Check the URL or choose a case from the board.`
            : `${error}. Return to the list and try again.`}
        </EmptyState>
      ) : detail ? (
        <Split
          sidebar={
            <Card
              title="Live applicant"
              subtitle="Fetched from the orchestrator · never stored here"
            >
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
            {detail.breached && (
              <Alert tone="negative" title="SLA breached">
                This active case is past its current deadline and requires
                immediate attention.
              </Alert>
            )}
            {detail.category === "OTHER" && (
              <CategorySuggestion
                suggestions={suggestions}
                loading={suggestionLoading}
                error={suggestionError}
              />
            )}
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

            {detail.status === "CLOSED" && (
              <section className="case-csat" aria-labelledby="case-csat-title">
                <div className="case-csat-heading">
                  <div>
                    <h3 id="case-csat-title">Customer satisfaction</h3>
                    <p>
                      A close-time score is recorded once and becomes part of the
                      case audit trail.
                    </p>
                  </div>
                  {detail.csatScore != null && (
                    <Badge tone={detail.csatScore >= 4 ? "positive" : "warning"}>
                      {detail.csatScore} / 5
                    </Badge>
                  )}
                </div>

                {detail.csatScore != null ? (
                  <div className="case-csat-recorded">
                    <strong>CSAT recorded</strong>
                    <span>{detail.csatComment || "No comment provided."}</span>
                    <small>This response cannot be edited or deleted.</small>
                  </div>
                ) : (
                  <form className="case-csat-form" onSubmit={submitCsat}>
                    <label>
                      Score
                      <select
                        value={csatScore}
                        onChange={(event) => setCsatScore(event.target.value)}
                        disabled={csatLoading}
                      >
                        <option value="5">5 — Excellent</option>
                        <option value="4">4 — Good</option>
                        <option value="3">3 — Fair</option>
                        <option value="2">2 — Poor</option>
                        <option value="1">1 — Very poor</option>
                      </select>
                    </label>
                    <label>
                      Comment (optional)
                      <textarea
                        value={csatComment}
                        onChange={(event) => setCsatComment(event.target.value)}
                        disabled={csatLoading}
                        rows={3}
                      />
                    </label>
                    {csatError && (
                      <Alert tone="negative" title="CSAT could not be recorded">
                        {csatError}
                      </Alert>
                    )}
                    <Button type="submit" disabled={csatLoading}>
                      {csatLoading ? "Recording..." : "Record CSAT"}
                    </Button>
                  </form>
                )}
              </section>
            )}

            {actions.length > 0 ? (
              <form className="case-transition-form" onSubmit={submitTransition}>
                <div className="case-work-heading">
                  <div>
                    <h3>Next action</h3>
                    <p>
                      Only transitions allowed from {detail.status} are
                      available.
                    </p>
                  </div>
                </div>
                <div className="case-transition-grid">
                  <label>
                    Action
                    <select
                      value={action}
                      onChange={(event) => setAction(event.target.value)}
                      disabled={transitionLoading}
                    >
                      {actions.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label>
                    Actor
                    <input
                      value={actor}
                      onChange={(event) => setActor(event.target.value)}
                      placeholder="agent id"
                      disabled={transitionLoading}
                      required
                    />
                  </label>

                  <label className="case-transition-note">
                    Note {requiresNote ? "(required for RESOLVE)" : "(optional)"}
                    <textarea
                      value={note}
                      onChange={(event) => setNote(event.target.value)}
                      disabled={transitionLoading}
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
                  variant="primary"
                  disabled={transitionLoading}
                >
                  {transitionLoading ? "Applying..." : "Apply transition"}
                </Button>
              </form>
            ) : (
              <div className="case-locked-state">
                <Badge tone="neutral">Terminal</Badge>
                <div>
                  <strong>No further lifecycle actions</strong>
                  <p>
                    CLOSED cases are immutable. Their timeline remains available
                    for audit.
                  </p>
                </div>
              </div>
            )}

            {supervisorActions.length > 0 && (
              <section
                className="case-supervisor-form"
                aria-label="Supervisor actions"
              >
                <div className="case-supervisor-header">
                  <div>
                    <h3>Supervisor actions</h3>
                    <p className="case-supervisor-meta">
                      Elevated actions are separated from day-to-day agent
                      transitions.
                    </p>
                  </div>
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() =>
                      setShowSupervisorActions((current) => !current)
                    }
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
                          onChange={(event) =>
                            setSupervisorAction(event.target.value)
                          }
                          disabled={supervisorLoading}
                        >
                          {supervisorActions.map((option) => (
                            <option key={option} value={option}>
                              {option}
                            </option>
                          ))}
                        </select>
                      </label>

                      <label>
                        Supervisor
                        <input
                          value={supervisor}
                          onChange={(event) => setSupervisor(event.target.value)}
                          placeholder="supervisor id"
                          disabled={supervisorLoading}
                          required
                        />
                      </label>

                      {supervisorAction === "REASSIGN" && (
                        <label>
                          Assignee
                          <input
                            value={supervisorAssignee}
                            onChange={(event) =>
                              setSupervisorAssignee(event.target.value)
                            }
                            placeholder="new assignee"
                            disabled={supervisorLoading}
                            required
                          />
                        </label>
                      )}

                      <label className="case-transition-note">
                        Reason (required)
                        <textarea
                          value={supervisorReason}
                          onChange={(event) =>
                            setSupervisorReason(event.target.value)
                          }
                          disabled={supervisorLoading}
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
                      variant={
                        supervisorAction === "FORCE_CLOSE"
                          ? "danger"
                          : "primary"
                      }
                      disabled={supervisorLoading}
                    >
                      {supervisorLoading
                        ? "Applying..."
                        : "Apply supervisor action"}
                    </Button>
                  </form>
                )}
              </section>
            )}
          </Card>

          <Card
            title="Case timeline"
            subtitle={`${timeline.length} auditable ${timeline.length === 1 ? "event" : "events"} · oldest first`}
          >
            {timeline.length > 0 ? (
              <Timeline items={timeline} />
            ) : (
              <EmptyState flush title="No timeline events">
                This case has no recorded history.
              </EmptyState>
            )}
          </Card>
        </Split>
      ) : null}
    </>
  );
}

function CategorySuggestion({ suggestions = [], loading, error }) {
  if (loading) {
    return (
      <div className="category-suggestion" aria-live="polite">
        <Spinner label="Checking category suggestions" />
        <span>Checking the current keyword policy…</span>
      </div>
    );
  }
  if (error) {
    return (
      <Alert tone="warning" title="Category suggestion unavailable">
        The case is unchanged. {error}
      </Alert>
    );
  }
  if (suggestions.length === 0) return null;

  const top = suggestions[0];
  return (
    <Alert tone="info" title={`Suggested category: ${top.category}`}>
      <p>
        Matched {top.score} distinct {top.score === 1 ? "keyword" : "keywords"}:{" "}
        {top.matchedKeywords.join(", ")}.
      </p>
      <p>This is guidance only; the customer&apos;s stored category remains OTHER.</p>
    </Alert>
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
