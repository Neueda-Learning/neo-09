import React, { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Caption,
  Card,
  ChipGroup,
  DataTable,
  EmptyState,
  Grid,
  KeyValue,
  MetricTile,
  PageHeader,
  SearchInput,
  Spinner,
  Toolbar,
} from '../design-system';
import { caseStatusTone, priorityTone, time } from '../status.js';

const PRIORITIES = ['All', 'P1', 'P2', 'P3'];

export default function CaseBoardScreen({ cases, error, loading, info, onViewConfig }) {
  const [query, setQuery] = useState('');
  const [priority, setPriority] = useState('All');
  const [selectedCaseId, setSelectedCaseId] = useState(null);

  const counts = useMemo(
    () =>
      cases.reduce((acc, supportCase) => {
        const key = supportCase.priority ?? 'Pricing';
        acc[key] = (acc[key] ?? 0) + 1;
        return acc;
      }, {}),
    [cases]
  );

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return cases.filter((supportCase) => {
      if (priority !== 'All' && supportCase.priority !== priority) return false;
      if (!needle) return true;
      return [
        supportCase.caseId,
        supportCase.applicationId,
        supportCase.category,
        supportCase.description,
      ].some((value) => value?.toLowerCase().includes(needle));
    });
  }, [cases, priority, query]);
  const selectedCase = useMemo(
    () => cases.find((supportCase) => supportCase.caseId === selectedCaseId) ?? null,
    [cases, selectedCaseId]
  );

  const columns = [
    {
      key: 'caseId',
      header: 'Case',
      mono: true,
      render: (supportCase) => (
        <span title={supportCase.caseId}>{supportCase.caseId.replace('case-', '').slice(0, 8)}</span>
      ),
    },
    { key: 'category', header: 'Category' },
    {
      key: 'priority',
      header: 'Priority',
      tight: true,
      render: (supportCase) => (
        <Badge tone={priorityTone(supportCase.priority)}>
          {supportCase.priority ?? 'Pricing'}
        </Badge>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      tight: true,
      render: (supportCase) => (
        <Badge tone={caseStatusTone(supportCase.status)}>{supportCase.status}</Badge>
      ),
    },
    { key: 'openedAt', header: 'Opened', render: (supportCase) => time(supportCase.openedAt) },
    {
      key: 'slaDeadline',
      header: 'SLA deadline',
      render: (supportCase) =>
        supportCase.slaDeadline ? time(supportCase.slaDeadline) : 'Pricing…',
    },
    {
      key: 'details',
      header: 'Details',
      tight: true,
      render: (supportCase) => (
        <Button
          size="sm"
          variant="ghost"
          aria-expanded={supportCase.caseId === selectedCaseId}
          onClick={() =>
            setSelectedCaseId((current) =>
              current === supportCase.caseId ? null : supportCase.caseId
            )
          }
        >
          {supportCase.caseId === selectedCaseId ? 'Hide' : 'View'}
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

      <Grid cols={3} min={160} style={{ marginBottom: 'var(--ds-space-6)' }}>
        <MetricTile label="On the board" value={cases.length} />
        <MetricTile label="New" value={cases.filter((c) => c.status === 'NEW').length} tone="info" />
        <MetricTile label="P1 priority" value={counts.P1 ?? 0} tone="negative" />
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
          total={matches.length}
          rowKey={(supportCase) => supportCase.caseId}
          footnote="newest first · API capped at 10"
          empty={
            <EmptyState title={cases.length === 0 ? 'No support cases yet' : 'No case matches that'}>
              {cases.length === 0 ? (
                <>
                  Send an <strong>open-case</strong> command through the orchestrator. This board is
                  deliberately read-only: the customer journey is the only intake path.
                </>
              ) : (
                <>Clear the search or choose a different priority.</>
              )}
            </EmptyState>
          }
        />
      )}

      {selectedCase && (
        <Card
          className="case-detail-card"
          title={selectedCase.caseId}
          subtitle={selectedCase.category}
          headEnd={
            <Badge tone={caseStatusTone(selectedCase.status)}>{selectedCase.status}</Badge>
          }
          foot={
            <Button
              variant="secondary"
              disabled={selectedCase.configVersion == null}
              onClick={() => onViewConfig?.(selectedCase.configVersion)}
            >
              View config v{selectedCase.configVersion ?? '—'}
            </Button>
          }
        >
          <KeyValue
            items={[
              { label: 'Application', value: selectedCase.applicationId, mono: true },
              { label: 'Priority', value: selectedCase.priority ?? 'Pricing' },
              { label: 'Opened', value: time(selectedCase.openedAt) },
              {
                label: 'SLA deadline',
                value: selectedCase.slaDeadline ? time(selectedCase.slaDeadline) : 'Pricing…',
              },
              {
                label: 'Pinned configuration',
                value: selectedCase.configVersion ? `v${selectedCase.configVersion}` : 'Pricing…',
              },
              { label: 'Description', value: selectedCase.description },
            ]}
          />
          <Caption>
            The pinned configuration explains this case’s priority and deadline and never changes
            when a newer policy is created.
          </Caption>
        </Card>
      )}
    </>
  );
}
