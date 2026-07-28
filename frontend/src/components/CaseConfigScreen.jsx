import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  DataTable,
  EmptyState,
  Field,
  FormActions,
  FormGrid,
  PageHeader,
  Select,
  Split,
  TextInput,
  Textarea,
} from '../design-system';
import { api } from '../api.js';

const DEFAULT_SLA = { P1: '4', P2: '24', P3: '72' };
const PRIORITY_OPTIONS = ['P1', 'P2', 'P3'];

function toCategoryLines(categories) {
  return categories.join('\n');
}

function parseCategoryLines(lines) {
  return lines
    .split('\n')
    .map((c) => c.trim())
    .filter(Boolean);
}

export default function CaseConfigScreen({ info }) {
  const [history, setHistory] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState(null);
  const [categoriesText, setCategoriesText] = useState('');
  const [priorityMap, setPriorityMap] = useState({});
  const [slaHours, setSlaHours] = useState(DEFAULT_SLA);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);

  const loadHistory = useCallback(async () => {
    try {
      const versions = await api.listConfigVersions();
      setHistory(versions);
      if (versions.length > 0) {
        const current = versions.find((v) => v.current) ?? versions[versions.length - 1];
        setSelectedVersion(current.version);
        setCategoriesText(toCategoryLines(current.categories));
        setPriorityMap(current.priorityMap);
        setSlaHours({
          P1: String(current.slaHours.P1),
          P2: String(current.slaHours.P2),
          P3: String(current.slaHours.P3),
        });
      }
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const categories = useMemo(() => parseCategoryLines(categoriesText), [categoriesText]);

  const selected = useMemo(
    () => history.find((version) => version.version === selectedVersion) ?? null,
    [history, selectedVersion]
  );

  const historyColumns = [
    { key: 'version', header: 'Version', tight: true, render: (row) => `v${row.version}` },
    {
      key: 'effectiveFrom',
      header: 'Effective from',
      render: (row) => new Date(row.effectiveFrom).toLocaleString(),
    },
    {
      key: 'current',
      header: 'State',
      tight: true,
      render: (row) => (row.current ? <Badge tone="positive">Current</Badge> : <Badge tone="neutral">History</Badge>),
    },
    {
      key: 'select',
      header: 'View',
      tight: true,
      render: (row) => (
        <Button
          size="sm"
          variant={row.version === selectedVersion ? 'primary' : 'ghost'}
          onClick={() => setSelectedVersion(row.version)}
        >
          {row.version === selectedVersion ? 'Selected' : 'Select'}
        </Button>
      ),
    },
  ];

  const onCategoriesChange = (value) => {
    setCategoriesText(value);
    const nextCategories = parseCategoryLines(value);
    setPriorityMap((prev) => {
      const next = {};
      for (const category of nextCategories) {
        next[category] = prev[category] ?? 'P3';
      }
      return next;
    });
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitting(true);
    setSuccess(null);
    try {
      const payload = {
        categories,
        priorityMap: categories.reduce((acc, category) => {
          acc[category] = priorityMap[category] ?? 'P3';
          return acc;
        }, {}),
        slaHours: {
          P1: Number(slaHours.P1),
          P2: Number(slaHours.P2),
          P3: Number(slaHours.P3),
        },
      };
      const created = await api.createConfig(payload);
      setSuccess(`Created config version v${created.version}`);
      await loadHistory();
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Case configuration"
        lede="edit taxonomy, priority mapping and SLA hours as data; each save creates a new immutable version"
        meta={
          info
            ? `${info.serviceId} · ${info.domain} · current version is computed as MAX(version)`
            : undefined
        }
      />

      {error && (
        <Alert tone="negative" title="Configuration request failed">
          {error}
        </Alert>
      )}

      {success && (
        <Alert tone="positive" title="Configuration saved">
          {success}
        </Alert>
      )}

      <Split
        sidebar={
          loading ? (
            <EmptyState title="Loading history">Fetching config versions…</EmptyState>
          ) : (
            <div className="config-history-pane">
              <h3>Version history</h3>
              <DataTable
                columns={historyColumns}
                rows={history}
                rowKey={(row) => row.version}
                total={history.length}
                footnote="oldest first · current is MAX(version)"
                empty={<EmptyState title="No configuration versions">No rows found.</EmptyState>}
              />
              {selected && (
                <div className="config-history-detail">
                  <h4>v{selected.version} snapshot</h4>
                  <Field label="Categories" htmlFor="snapshot-categories">
                    {({ id }) => (
                      <Textarea id={id} value={toCategoryLines(selected.categories)} readOnly rows={6} mono />
                    )}
                  </Field>
                  <Field label="Priority map" htmlFor="snapshot-priority">
                    {({ id }) => (
                      <Textarea
                        id={id}
                        value={JSON.stringify(selected.priorityMap, null, 2)}
                        readOnly
                        rows={8}
                        mono
                      />
                    )}
                  </Field>
                  <Field label="SLA hours" htmlFor="snapshot-sla">
                    {({ id }) => (
                      <Textarea id={id} value={JSON.stringify(selected.slaHours, null, 2)} readOnly rows={4} mono />
                    )}
                  </Field>
                </div>
              )}
            </div>
          )
        }
      >
        <form onSubmit={onSubmit}>
          <div className="config-editor-pane">
            <h3>Create new version</h3>
            <FormGrid cols={2}>
              <FormGrid.Full>
                <Field
                  label="Categories"
                  hint="One category per line, UPPER_SNAKE."
                  htmlFor="config-categories"
                >
                  {({ id }) => (
                    <Textarea
                      id={id}
                      rows={8}
                      mono
                      value={categoriesText}
                      onChange={(event) => onCategoriesChange(event.target.value)}
                    />
                  )}
                </Field>
              </FormGrid.Full>

              {categories.length > 0 ? (
                categories.map((category) => (
                  <Field key={category} label={`Priority for ${category}`} htmlFor={`priority-${category}`}>
                    {({ id }) => (
                      <Select
                        id={id}
                        options={PRIORITY_OPTIONS}
                        value={priorityMap[category] ?? 'P3'}
                        onChange={(event) =>
                          setPriorityMap((prev) => ({ ...prev, [category]: event.target.value }))
                        }
                      />
                    )}
                  </Field>
                ))
              ) : (
                <FormGrid.Full>
                  <EmptyState title="No categories yet">Add categories above to map priorities.</EmptyState>
                </FormGrid.Full>
              )}

              <Field label="SLA P1 (hours)" htmlFor="sla-p1">
                {({ id }) => (
                  <TextInput
                    id={id}
                    type="number"
                    min="1"
                    value={slaHours.P1}
                    onChange={(event) => setSlaHours((prev) => ({ ...prev, P1: event.target.value }))}
                  />
                )}
              </Field>
              <Field label="SLA P2 (hours)" htmlFor="sla-p2">
                {({ id }) => (
                  <TextInput
                    id={id}
                    type="number"
                    min="1"
                    value={slaHours.P2}
                    onChange={(event) => setSlaHours((prev) => ({ ...prev, P2: event.target.value }))}
                  />
                )}
              </Field>
              <Field label="SLA P3 (hours)" htmlFor="sla-p3">
                {({ id }) => (
                  <TextInput
                    id={id}
                    type="number"
                    min="1"
                    value={slaHours.P3}
                    onChange={(event) => setSlaHours((prev) => ({ ...prev, P3: event.target.value }))}
                  />
                )}
              </Field>
            </FormGrid>

            <FormActions>
              <Button type="submit" disabled={submitting}>
                {submitting ? 'Saving…' : 'Create version'}
              </Button>
            </FormActions>
          </div>
        </form>
      </Split>
    </>
  );
}
