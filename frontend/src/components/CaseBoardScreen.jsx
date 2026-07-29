import React, { useMemo } from "react";
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
  Select,
  Spinner,
  Toolbar,
} from "../design-system";
import { caseStatusTone, priorityTone, time } from "../status.js";

const PRIORITIES = ["All", "P1", "P2", "P3"];

export default function CaseBoardScreen({
  queue,
  query,
  onQueryChange,
  searchStatus,
  onSearchStatusChange,
  priority,
  onPriorityChange,
  searchResults,
  searchLoading,
  searchError,
  applicantNames,
  error,
  loading,
  info,
  onOpenCase,
}) {
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
  ];

  const clearFilters = () => {
    onQueryChange("");
    onSearchStatusChange("");
    onPriorityChange("All");
  };

  return (
    <>
      <PageHeader
        title="Case board"
        lede="Prioritised operational queue with live applicant hydration and capped search results."
        meta={
          info
            ? `${info.serviceId} · ${info.domain} · v${info.version} · maximum 10 rows`
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
          label="Visible queue rows"
          value={queue?.cases?.length ?? 0}
          hint="Maximum 10 · worst first"
        />
      </Grid>

      <Toolbar>
        <SearchInput
          grow
          placeholder="Search case ID, application ID or customer name"
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          aria-label="Search support cases"
        />
        <label className="case-board-status-filter">
          <span>Status</span>
          <Select
            size="sm"
            value={searchStatus}
            onChange={(event) => onSearchStatusChange(event.target.value)}
            disabled={!searching}
            aria-label="Filter search results by status"
            options={[
              { value: "", label: "All statuses" },
              "NEW",
              "OPEN",
              "PENDING_CUSTOMER",
              "RESOLVED",
              "CLOSED",
            ]}
          />
        </label>
        <ChipGroup
          options={PRIORITIES}
          value={priority}
          onChange={onPriorityChange}
          counts={counts}
        />
        {(searching || searchStatus || priority !== "All") && (
          <Button size="sm" variant="ghost" onClick={clearFilters}>
            Clear filters
          </Button>
        )}
      </Toolbar>

      <div className="case-search-guidance" aria-live="polite">
        {searching && query.trim().length < 3
          ? "Short searches can be broad. Add more characters for a more precise result."
          : searching && searchResults.length === 10
            ? "10 results shown, the search limit. Refine the case ID, application ID or customer name to narrow the list."
            : "Search accepts case ID, application ID or customer name. Clear the search to return to the priority queue."}
      </div>

      {loading || searchLoading ? (
        <div className="case-board-loading" aria-live="polite">
          <Spinner
            label={searching ? "Searching support cases" : "Loading support cases"}
          />
          <span>
            {searching ? "Searching support cases…" : "Loading support cases…"}
          </span>
        </div>
      ) : searchError ? (
        <EmptyState
          title="Search is temporarily unavailable"
          action={
            <Button variant="secondary" size="sm" onClick={clearFilters}>
              Return to queue
            </Button>
          }
        >
          {searchError}. The queue remains available while the applicant service
          recovers.
        </EmptyState>
      ) : (
        <DataTable
          aria-label={searching ? "Support case search results" : "Support case priority queue"}
          columns={columns}
          rows={matches}
          total={matches.length}
          rowKey={(supportCase) => supportCase.caseId}
          onRowClick={(supportCase) => onOpenCase?.(supportCase.caseId)}
          footnote={
            searching
              ? searchResults.length === 10
                ? "search results newest first · 10-row API limit reached — refine your search"
                : "search results newest first · API capped at 10"
              : "priority queue · breached first, then deadline · API capped at 10"
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
                <>
                  No matching case was found. Try a more precise case ID,
                  application ID, or a different customer name.
                </>
              )}
            </EmptyState>
          }
        />
      )}
    </>
  );
}
