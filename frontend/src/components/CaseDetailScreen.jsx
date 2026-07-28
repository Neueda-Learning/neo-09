import React, { useMemo } from "react";
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
  applicantError,
  applicantLoading,
  loading,
  detail,
  applicant,
}) {
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
          <Button variant="ghost" size="sm" onClick={onBack}>
            Back to board
          </Button>
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
