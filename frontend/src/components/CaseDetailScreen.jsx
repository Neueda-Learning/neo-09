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
  error,
  applicantError,
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
        action={
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
              {applicant ? (
                <KeyValue
                  stacked
                  items={[
                    ["Name", applicant.applicant?.fullName ?? "—"],
                    ["Product", applicant.product?.productCode ?? "—"],
                    [
                      "Delivery address",
                      applicant.delivery?.address
                        ? formatAddress(applicant.delivery.address)
                        : "—",
                    ],
                    ["Channel", applicant.channel ?? "—"],
                  ]}
                />
              ) : (
                <Alert
                  tone="warning"
                  title="application not found — link may be stale"
                >
                  {applicantError ?? "Retry to refresh the sidebar."}
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
