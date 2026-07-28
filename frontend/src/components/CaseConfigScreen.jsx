import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Caption,
  DataTable,
  EmptyState,
  Field,
  FormActions,
  FormGrid,
  KeyValue,
  PageHeader,
  Select,
  Split,
  TextInput,
  Textarea,
} from '../design-system';
import { api } from '../api.js';

const DEFAULT_SLA = { P1: '4', P2: '24', P3: '72' };
const PRIORITY_OPTIONS = ['P1', 'P2', 'P3'];
const UPPER_SNAKE = /^[A-Z][A-Z0-9_]*$/;

function toCategoryLines(categories) {
  return categories.join('\n');
}

function parseCategoryLines(lines) {
  return lines
    .split('\n')
    .map((category) => category.trim())
    .filter(Boolean);
}

function draftFrom(version) {
  return {
    categoriesText: toCategoryLines(version.categories),
    priorityMap: { ...version.priorityMap },
    slaHours: {
      P1: String(version.slaHours.P1),
      P2: String(version.slaHours.P2),
      P3: String(version.slaHours.P3),
    },
  };
}

function configSignature(categories, priorityMap, slaHours) {
  return JSON.stringify({
    categories,
    priorityMap: categories.reduce((result, category) => {
      result[category] = priorityMap[category] ?? 'P3';
      return result;
    }, {}),
    slaHours: {
      P1: Number(slaHours.P1),
      P2: Number(slaHours.P2),
      P3: Number(slaHours.P3),
    },
  });
}

function validateDraft(categories, slaHours) {
  const errors = {};
  if (categories.length === 0) {
    errors.categories = 'Add at least one category.';
  } else if (new Set(categories).size !== categories.length) {
    errors.categories = 'Each category must be unique.';
  } else {
    const invalid = categories.find((category) => !UPPER_SNAKE.test(category));
    if (invalid) {
      errors.categories = `${invalid} must use UPPER_SNAKE.`;
    }
  }

  const hours = PRIORITY_OPTIONS.map((priority) => Number(slaHours[priority]));
  if (hours.some((value) => !Number.isInteger(value) || value <= 0)) {
    errors.slaHours = 'Enter positive whole hours for every priority.';
  } else if (!(hours[0] < hours[1] && hours[1] < hours[2])) {
    errors.slaHours = 'SLA hours must increase from P1 to P3: P1 < P2 < P3.';
  }
  return errors;
}

function formatTimestamp(value) {
  return new Date(value).toISOString().slice(0, 16).replace('T', ' ') + ' UTC';
}

export default function CaseConfigScreen({ info, initialVersion }) {
  const [history, setHistory] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState(null);
  const [categoriesText, setCategoriesText] = useState('');
  const [priorityMap, setPriorityMap] = useState({});
  const [slaHours, setSlaHours] = useState(DEFAULT_SLA);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [requestError, setRequestError] = useState(null);
  const [serverFieldErrors, setServerFieldErrors] = useState({});
  const [success, setSuccess] = useState(null);

  const setDraft = useCallback((version) => {
    const draft = draftFrom(version);
    setCategoriesText(draft.categoriesText);
    setPriorityMap(draft.priorityMap);
    setSlaHours(draft.slaHours);
    setServerFieldErrors({});
  }, []);

  const loadHistory = useCallback(async ({ resetDraft = false, selectVersion } = {}) => {
    try {
      const versions = await api.listConfigVersions();
      setHistory(versions);
      if (versions.length > 0) {
        const current = versions.find((version) => version.current) ?? versions[versions.length - 1];
        const requested = versions.find((version) => version.version === selectVersion);
        setSelectedVersion(requested?.version ?? current.version);
        if (resetDraft) setDraft(current);
      }
      setRequestError(null);
    } catch (error) {
      setRequestError(error.message);
    } finally {
      setLoading(false);
    }
  }, [setDraft]);

  useEffect(() => {
    loadHistory({ resetDraft: true, selectVersion: initialVersion });
  }, [initialVersion, loadHistory]);

  const categories = useMemo(() => parseCategoryLines(categoriesText), [categoriesText]);
  const current = useMemo(
    () => history.find((version) => version.current) ?? history[history.length - 1] ?? null,
    [history]
  );
  const selected = useMemo(
    () => history.find((version) => version.version === selectedVersion) ?? null,
    [history, selectedVersion]
  );
  const clientErrors = useMemo(
    () => validateDraft(categories, slaHours),
    [categories, slaHours]
  );
  const fieldErrors = {
    categories: serverFieldErrors.categories?.join(' ') ?? clientErrors.categories,
    priorityMap: serverFieldErrors.priorityMap?.join(' '),
    slaHours: serverFieldErrors.slaHours?.join(' ') ?? clientErrors.slaHours,
  };
  const dirty = current
    ? configSignature(categories, priorityMap, slaHours)
      !== configSignature(current.categories, current.priorityMap, current.slaHours)
    : false;
  const canSubmit = dirty && Object.keys(clientErrors).length === 0 && !submitting;

  const historyColumns = [
    { key: 'version', header: 'Version', tight: true, render: (row) => `v${row.version}` },
    {
      key: 'effectiveFrom',
      header: 'Effective from',
      render: (row) => formatTimestamp(row.effectiveFrom),
    },
    {
      key: 'current',
      header: 'State',
      tight: true,
      render: (row) => (
        <Badge tone={row.current ? 'positive' : 'neutral'}>
          {row.current ? 'Current' : 'Superseded'}
        </Badge>
      ),
    },
    {
      key: 'select',
      header: 'Snapshot',
      tight: true,
      render: (row) => (
        <Button
          size="sm"
          variant="ghost"
          aria-pressed={row.version === selectedVersion}
          onClick={() => setSelectedVersion(row.version)}
        >
          {row.version === selectedVersion ? 'Viewing' : 'View'}
        </Button>
      ),
    },
  ];

  const snapshotPriorityColumns = [
    { key: 'category', header: 'Category' },
    {
      key: 'priority',
      header: 'Priority',
      tight: true,
      render: (row) => <Badge tone="neutral">{row.priority}</Badge>,
    },
  ];

  const onCategoriesChange = (value) => {
    setCategoriesText(value);
    setSuccess(null);
    setServerFieldErrors({});
    const nextCategories = parseCategoryLines(value);
    setPriorityMap((previous) => {
      const next = {};
      for (const category of nextCategories) {
        next[category] = previous[category] ?? 'P3';
      }
      return next;
    });
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    if (!canSubmit) return;

    setSubmitting(true);
    setSuccess(null);
    setRequestError(null);
    setServerFieldErrors({});
    try {
      const payload = {
        categories,
        priorityMap: categories.reduce((result, category) => {
          result[category] = priorityMap[category] ?? 'P3';
          return result;
        }, {}),
        slaHours: {
          P1: Number(slaHours.P1),
          P2: Number(slaHours.P2),
          P3: Number(slaHours.P3),
        },
      };
      const created = await api.createConfig(payload);
      setSuccess(`Version v${created.version} is now current. Existing cases keep their pinned version.`);
      await loadHistory({ resetDraft: true, selectVersion: created.version });
    } catch (error) {
      setRequestError(error.message);
      setServerFieldErrors(error.fieldErrors ?? {});
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Case configuration"
        lede="Create immutable desk-policy versions; changes price new cases only and never rewrite existing cases."
        meta={
          info
            ? `${info.serviceId} · ${info.domain} · current is always MAX(version)`
            : undefined
        }
      />

      {requestError && (
        <Alert tone="negative" title="Configuration request failed">
          {requestError}
        </Alert>
      )}

      {success && (
        <Alert tone="positive" title="Configuration saved" aria-live="polite">
          {success}
        </Alert>
      )}

      <Split
        ratio="even"
        sidebar={
          loading ? (
            <EmptyState title="Loading version history">Fetching immutable snapshots…</EmptyState>
          ) : (
            <div className="config-history-pane">
              <div className="config-section-heading">
                <div>
                  <h2>Version history</h2>
                  <Caption>Oldest first · every saved version remains available for audit.</Caption>
                </div>
              </div>
              <DataTable
                columns={historyColumns}
                rows={history}
                rowKey={(row) => row.version}
                maxRows={null}
                total={history.length}
                selectedKey={selectedVersion}
                footnote="insert-only history · current is MAX(version)"
                empty={
                  <EmptyState title="No configuration versions">
                    The seed should create v1 on first boot.
                  </EmptyState>
                }
              />
              {selected && (
                <Card
                  title={`Version v${selected.version}`}
                  subtitle={formatTimestamp(selected.effectiveFrom)}
                  headEnd={
                    <Badge tone={selected.current ? 'positive' : 'neutral'}>
                      {selected.current ? 'Current' : 'Read-only'}
                    </Badge>
                  }
                >
                  <DataTable
                    columns={snapshotPriorityColumns}
                    rows={selected.categories.map((category) => ({
                      category,
                      priority: selected.priorityMap[category],
                    }))}
                    rowKey={(row) => row.category}
                    maxRows={null}
                    footnote={`${selected.categories.length} categories in this snapshot`}
                  />
                  <KeyValue
                    items={PRIORITY_OPTIONS.map((priority) => ({
                      label: `${priority} SLA`,
                      value: `${selected.slaHours[priority]} hours`,
                    }))}
                  />
                  <Caption>
                    This snapshot is immutable. To reuse an older policy, create a new version from it.
                  </Caption>
                </Card>
              )}
            </div>
          )
        }
      >
        <form onSubmit={onSubmit} noValidate>
          <div className="config-editor-pane">
            <div className="config-section-heading">
              <div>
                <h2>Create new version</h2>
                <Caption>
                  Drafted from {current ? `current v${current.version}` : 'the current version'}.
                  Viewing history never changes this draft.
                </Caption>
              </div>
              {current && <Badge tone="positive">Based on v{current.version}</Badge>}
            </div>

            <FormGrid cols={2}>
              <FormGrid.Full>
                <Field
                  label="Categories"
                  hint="One per line. Use UPPER_SNAKE; removed categories remain on older cases."
                  error={fieldErrors.categories}
                  required
                  htmlFor="config-categories"
                >
                  {({ id, invalid, describedBy }) => (
                    <Textarea
                      id={id}
                      rows={8}
                      mono
                      value={categoriesText}
                      aria-invalid={invalid || undefined}
                      aria-describedby={describedBy}
                      onChange={(event) => onCategoriesChange(event.target.value)}
                    />
                  )}
                </Field>
              </FormGrid.Full>

              <FormGrid.Full>
                <div className="config-subsection-heading">
                  <h3>Priority mapping</h3>
                  <span>Every category needs one priority.</span>
                </div>
                {fieldErrors.priorityMap && (
                  <Alert tone="negative" title="Priority mapping is invalid">
                    {fieldErrors.priorityMap}
                  </Alert>
                )}
              </FormGrid.Full>

              {categories.length > 0 && !clientErrors.categories ? (
                categories.map((category, index) => (
                  <Field
                    key={`${category}-${index}`}
                    label={`Priority for ${category}`}
                    htmlFor={`priority-${index}`}
                  >
                    {({ id }) => (
                      <Select
                        id={id}
                        options={PRIORITY_OPTIONS}
                        value={priorityMap[category] ?? 'P3'}
                        onChange={(event) => {
                          setSuccess(null);
                          setPriorityMap((previous) => ({
                            ...previous,
                            [category]: event.target.value,
                          }));
                        }}
                      />
                    )}
                  </Field>
                ))
              ) : (
                <FormGrid.Full>
                  <EmptyState
                    title={categories.length === 0 ? 'Add a category to continue' : 'Fix categories to continue'}
                  >
                    Priority controls appear after the category list is valid.
                  </EmptyState>
                </FormGrid.Full>
              )}

              <FormGrid.Full>
                <div className="config-subsection-heading">
                  <h3>SLA hours</h3>
                  <span>Urgent cases must always have the shortest deadline.</span>
                </div>
              </FormGrid.Full>

              {PRIORITY_OPTIONS.map((priority) => (
                <Field
                  key={priority}
                  label={`${priority} SLA (hours)`}
                  error={priority === 'P1' ? fieldErrors.slaHours : undefined}
                  required
                  htmlFor={`sla-${priority.toLowerCase()}`}
                >
                  {({ id, invalid, describedBy }) => (
                    <TextInput
                      id={id}
                      type="number"
                      min="1"
                      step="1"
                      value={slaHours[priority]}
                      aria-invalid={invalid || undefined}
                      aria-describedby={describedBy}
                      onChange={(event) => {
                        setSuccess(null);
                        setServerFieldErrors({});
                        setSlaHours((previous) => ({
                          ...previous,
                          [priority]: event.target.value,
                        }));
                      }}
                    />
                  )}
                </Field>
              ))}
            </FormGrid>

            <FormActions>
              <Button
                variant="ghost"
                disabled={!dirty || submitting}
                onClick={() => current && setDraft(current)}
              >
                Reset draft
              </Button>
              <Button
                type="submit"
                variant="primary"
                busy={submitting}
                busyLabel="Creating version…"
                disabled={!canSubmit}
              >
                Create version
              </Button>
            </FormActions>
            {!dirty && current && (
              <Caption role="status">
                Draft matches current v{current.version}. Change a rule to create a new version.
              </Caption>
            )}
          </div>
        </form>
      </Split>
    </>
  );
}
