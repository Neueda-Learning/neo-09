import React, { useMemo, useState } from "react";
import {
  Alert,
  Badge,
  ChipGroup,
  DataTable,
  EmptyState,
  Grid,
  MetricTile,
  PageHeader,
  SearchInput,
  Spinner,
  Toolbar,
} from "../design-system";
import { caseStatusTone, priorityTone, time } from "../status.js";

const PRIORITIES = ["All", "P1", "P2", "P3"];

export default function CaseBoardScreen({
  queue,
  error,
  loading,
  info,
  onOpenCase,
}) {
  const [query, setQuery] = useState("");
  const [priority, setPriority] = useState("All");

  const rows = queue?.cases ?? [];

  const counts = useMemo(
    () =>
      rows.reduce((acc, supportCase) => {
        const key = supportCase.priority ?? "Pricing";
        acc[key] = (acc[key] ?? 0) + 1;
        return acc;
      }, {}),
    [rows],
  );

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return rows.filter((supportCase) => {
      if (priority !== "All" && supportCase.priority !== priority) return false;
      if (!needle) return true;
      return [
        supportCase.caseId,
        supportCase.applicationId,
        supportCase.category,
        supportCase.description,
      ].some((value) => value?.toLowerCase().includes(needle));
    });
  }, [rows, priority, query]);

  const columns = [
    {
      key: "caseId",
      header: "Case",
      mono: true,
      render: (supportCase) => (
        <span title={supportCase.caseId}>
          {supportCase.caseId.replace("case-", "").slice(0, 8)}
        </span>
      ),
    },
    {
      key: "applicationId",
      header: "Application",
      mono: true,
      render: (supportCase) => supportCase.applicationId ?? "—",
    },
    { key: "category", header: "Category" },
    {
      key: "priority",
      header: "Priority",
      tight: true,
      render: (supportCase) => (
        <Badge tone={priorityTone(supportCase.priority)}>
          {supportCase.priority ?? "Pricing"}
        </Badge>
      ),
    },
    {
      key: "status",
      header: "Status",
      tight: true,
      render: (supportCase) => (
        <Badge tone={caseStatusTone(supportCase.status)}>
          {supportCase.status}
        </Badge>
      ),
    },
    {
      key: "openedAt",
      header: "Opened",
      render: (supportCase) => time(supportCase.openedAt),
    },
    {
      key: "slaDeadline",
      header: "SLA deadline",
      render: (supportCase) =>
        supportCase.slaDeadline ? time(supportCase.slaDeadline) : "Pricing…",
    },
  ];

  return (
    <>
      <PageHeader
        title="Support cases"
        lede="new customer cases are committed before acknowledgement, then priced from the current SLA configuration"
        meta={
          info
            ? `${info.serviceId} · ${info.domain} · v${info.version} · showing the latest 10 cases`
            : undefined
        }
      />

      {error && (
        <Alert tone="negative" title="Could not load support cases">
          {error} — the board retries every two seconds.
        </Alert>
      )}

      <Grid cols={3} min={160} style={{ marginBottom: "var(--ds-space-6)" }}>
        <MetricTile label="Open cases" value={queue?.totalOpen ?? 0} />
        <MetricTile
          label="Breached"
          value={queue?.breached ?? 0}
          tone="negative"
        />
        <MetricTile
          label="P1 priority"
          value={counts.P1 ?? 0}
          tone="negative"
        />
      </Grid>

      <Toolbar>
        <SearchInput
          grow
          placeholder="Case, application, category or description"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Search support cases"
        />
        <ChipGroup
          options={PRIORITIES}
          value={priority}
          onChange={setPriority}
          counts={counts}
        />
      </Toolbar>

      {loading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner label="Loading support cases" />
          <span>Loading support cases…</span>
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={matches}
          total={queue?.totalOpen ?? matches.length}
          rowKey={(supportCase) => supportCase.caseId}
          onRowClick={(supportCase) => onOpenCase?.(supportCase.caseId)}
          footnote="newest first · API capped at 10"
          empty={
            <EmptyState
              title={
                rows.length === 0
                  ? "No support cases yet"
                  : "No case matches that"
              }
            >
              {rows.length === 0 ? (
                <>
                  Send an <strong>open-case</strong> command through the
                  orchestrator. This board is deliberately read-only: the
                  customer journey is the only intake path.
                </>
              ) : (
                <>Clear the search or choose a different priority.</>
              )}
            </EmptyState>
          }
        />
      )}
    </>
  );
}
