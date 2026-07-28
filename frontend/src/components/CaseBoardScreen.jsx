import React, { useMemo, useState } from "react";
import {
  Alert,
  Badge,
  Button,
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
  query,
  onQueryChange,
  searchResults,
  searchLoading,
  searchError,
  applicantNames,
  error,
  loading,
  info,
  onOpenCase,
}) {
  const [priority, setPriority] = useState("All");

  const searching = Boolean(query.trim());
  const rows = searching ? searchResults : (queue?.cases ?? []);

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
    return rows.filter(
      (supportCase) =>
        priority === "All" || supportCase.priority === priority,
    );
  }, [rows, priority]);

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
      key: "applicant",
      header: "Applicant",
      render: (supportCase) =>
        applicantNames[supportCase.caseId] ?? "Loading…",
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
    {
      key: 'details',
      header: 'Details',
      tight: true,
      render: (supportCase) => (
        <Button
          size="sm"
          variant="ghost"
          onClick={(event) => {
            event.stopPropagation();
            onOpenCase?.(supportCase.caseId);
          }}
        >
          View
        </Button>
      ),
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
      {searchError && (
        <Alert tone="warning" title="Could not complete that search">
          {searchError}. Try again when the orchestrator is available.
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
          placeholder="Case ID, application ID or applicant name"
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          aria-label="Search support cases"
        />
        <ChipGroup
          options={PRIORITIES}
          value={priority}
          onChange={setPriority}
          counts={counts}
        />
      </Toolbar>

      {loading || searchLoading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner
            label={searching ? "Searching support cases" : "Loading support cases"}
          />
          <span>
            {searching ? "Searching support cases…" : "Loading support cases…"}
          </span>
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={matches}
          total={
            searching ? matches.length : (queue?.totalOpen ?? matches.length)
          }
          rowKey={(supportCase) => supportCase.caseId}
          onRowClick={(supportCase) => onOpenCase?.(supportCase.caseId)}
          footnote={
            searching
              ? "search results newest first · API capped at 10"
              : "priority queue · worst first · API capped at 10"
          }
          empty={
            <EmptyState
              title={
                searching ? "No case matches that" : "No open support cases"
              }
            >
              {!searching ? (
                <>
                  Send an <strong>open-case</strong> command through the
                  orchestrator. This board is deliberately read-only: the
                  customer journey is the only intake path.
                </>
              ) : (
                <>Refine the search or try a case or application ID.</>
              )}
            </EmptyState>
          }
        />
      )}
    </>
  );
}
