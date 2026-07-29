import React from "react";
import {
  Alert,
  Badge,
  DataTable,
  EmptyState,
  Grid,
  MetricTile,
  PageHeader,
  Spinner,
} from "../design-system";
import { priorityTone, time } from "../status.js";

/**
 * UC 04 · SLA & Breach View — read-only supervisor screen: per-priority open/breached tallies
 * (AC 1, 5, 6) and the worst breaches, capped at 10, worst (most overdue) first (AC 4, 5, 6).
 * An empty case book renders zeros, never an error (AC 7).
 */
export default function SlaBoardScreen({ sla, loading, error, info, onOpenCase }) {
  const byPriority = sla?.byPriority ?? [];
  const breachedCases = sla?.breachedCases ?? [];
  const csatByCategory = sla?.csatByCategory ?? [];

  const columns = [
    {
      key: "caseId",
      header: "Case",
      mono: true,
      render: (row) => <span title={row.caseId}>{row.caseId}</span>,
    },
    {
      key: "priority",
      header: "Priority",
      tight: true,
      render: (row) => <Badge tone={priorityTone(row.priority)}>{row.priority}</Badge>,
    },
    {
      key: "overdueHours",
      header: "Hours over",
      render: (row) => row.overdueHours.toFixed(1),
    },
  ];

  return (
    <>
      <PageHeader
        title="SLA oversight"
        lede="Open load by priority and the ten most overdue cases requiring intervention."
        meta={
          sla?.referenceNow
            ? `reference now ${time(sla.referenceNow)}${info ? ` · ${info.serviceId}` : ""}`
            : info
              ? `${info.serviceId} · ${info.domain}`
              : undefined
        }
      />

      {error && (
        <Alert tone="negative" title="Could not load the SLA board">
          {error} — this screen retries every two seconds.
        </Alert>
      )}

      {loading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner label="Loading SLA board" />
          <span>Loading SLA board…</span>
        </div>
      ) : (
        <>
          <Grid cols={3} min={160} style={{ marginBottom: "var(--ds-space-6)" }}>
            {byPriority.map((tile) => (
              <MetricTile
                key={tile.priority}
                label={`${tile.priority} open · breached`}
                value={`${tile.open} · ${tile.breached}`}
                tone={tile.breached > 0 ? "negative" : undefined}
              />
            ))}
          </Grid>

          <section className="sla-csat" aria-labelledby="sla-csat-title">
            <div className="sla-csat-heading">
              <h2 id="sla-csat-title">Average CSAT by category</h2>
              <p>CLOSED cases with a recorded score · one decimal</p>
            </div>
            <Grid cols={3} min={180} style={{ marginBottom: "var(--ds-space-6)" }}>
              {csatByCategory.map((tile) => (
                <MetricTile
                  key={tile.category}
                  label={tile.category.replaceAll("_", " ")}
                  value={
                    tile.averageScore == null
                      ? "—"
                      : `${Number(tile.averageScore).toFixed(1)} / 5.0`
                  }
                  tone={
                    tile.averageScore == null
                      ? undefined
                      : tile.averageScore < 3
                        ? "warning"
                        : "positive"
                  }
                />
              ))}
            </Grid>
          </section>

          <DataTable
            aria-label="Worst SLA breaches"
            columns={columns}
            rows={breachedCases}
            total={breachedCases.length}
            rowKey={(row) => row.caseId}
            onRowClick={(row) => onOpenCase?.(row.caseId)}
            rowTone={() => "negative"}
            footnote="worst breaches · most overdue first · capped at 10"
            empty={
              <EmptyState title="No breaches right now">
                Every open case is inside its SLA deadline.
              </EmptyState>
            }
          />
        </>
      )}
    </>
  );
}
