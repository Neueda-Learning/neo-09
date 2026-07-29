import React, { useMemo, useRef, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  Field,
  FormActions,
  KeyValue,
  Select,
  Textarea,
} from "../../src/design-system";
import {
  DEFAULT_ENVIRONMENT,
  ENVIRONMENTS,
  submitCustomerCase,
} from "./customer-api.js";

const CATEGORY_OPTIONS = [
  { value: "APPLICATION_STATUS", label: "Application status" },
  { value: "CARD_NOT_ARRIVED", label: "My card has not arrived" },
  { value: "AGREEMENT_QUESTION", label: "A question about my agreement" },
  { value: "DATA_CORRECTION", label: "Correct my personal details" },
  { value: "COMPLAINT", label: "Make a complaint" },
  { value: "OTHER", label: "Something else" },
];

const CATEGORY_HELP = {
  APPLICATION_STATUS: "Ask about the progress of an application you have already started.",
  CARD_NOT_ARRIVED: "Tell us when you expected your card and what delivery update you received.",
  AGREEMENT_QUESTION: "Include the section or term you would like us to explain.",
  DATA_CORRECTION: "Tell us which detail needs correcting. Do not include document numbers here.",
  COMPLAINT: "Describe what happened, when it happened, and how you would like us to help.",
  OTHER: "Give us enough detail to route your request to the right support team.",
};

const MAX_DESCRIPTION = 2000;

export default function CustomerCaseScreen() {
  const [environment, setEnvironment] = useState(DEFAULT_ENVIRONMENT);
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [acknowledgement, setAcknowledgement] = useState(null);
  const requestIdRef = useRef(crypto.randomUUID());
  const confirmationRef = useRef(null);

  const descriptionLength = description.trim().length;
  const descriptionError = useMemo(() => {
    if (!touched) return null;
    if (!description.trim()) return "Tell us what happened.";
    return null;
  }, [description, descriptionLength, touched]);
  const categoryError = touched && !category ? "Choose the topic that best fits your request." : null;
  const valid =
    Boolean(category) &&
    descriptionLength > 0 &&
    descriptionLength <= MAX_DESCRIPTION;

  const submit = async (event) => {
    event.preventDefault();
    setTouched(true);
    if (!valid) return;

    setSubmitting(true);
    setError(null);
    try {
      const result = await submitCustomerCase(
        { category, description: description.trim() },
        environment,
        requestIdRef.current,
      );
      setAcknowledgement(result);
      window.setTimeout(() => confirmationRef.current?.focus(), 0);
    } catch (submissionError) {
      setError(submissionError.message);
    } finally {
      setSubmitting(false);
    }
  };

  const startAnother = () => {
    setCategory("");
    setDescription("");
    setTouched(false);
    setError(null);
    setAcknowledgement(null);
    requestIdRef.current = crypto.randomUUID();
  };

  return (
    <div className="customer-app">
      <header className="customer-header">
        <a className="customer-brand" href="/" aria-label="Neo home">
          <span className="customer-brand__mark" aria-hidden="true">N</span>
          <span>
            <strong>Neo</strong>
            <small>Personal banking</small>
          </span>
        </a>
        <div className="customer-header__tools">
          <label className="customer-environment">
            <span>Environment</span>
            <select
              value={environment}
              onChange={(event) => {
                setEnvironment(event.target.value);
                setAcknowledgement(null);
                setError(null);
              }}
              aria-label="Request environment"
            >
              {Object.entries(ENVIRONMENTS).map(([value, item]) => (
                <option key={value} value={value}>{item.label}</option>
              ))}
            </select>
          </label>
          <div className="customer-header__account" aria-label="Signed-in customer">
            <span className="customer-avatar" aria-hidden="true">MN</span>
            <span>
              <small>Signed in as</small>
              <strong>Maria Nowak</strong>
            </span>
          </div>
        </div>
      </header>

      <main className="customer-main">
        <section className="customer-intro" aria-labelledby="support-title">
          <Badge tone="positive">Secure support</Badge>
          <h1 id="support-title">How can we help?</h1>
          <p className="customer-intro__lede">
            Send our support team a message about your application, card, or account.
            We will route it to the right person and keep you updated.
          </p>

          <ol className="customer-steps" aria-label="What happens next">
            <li>
              <span>1</span>
              <div>
                <strong>Tell us what happened</strong>
                <p>Choose a topic and describe the issue in your own words.</p>
              </div>
            </li>
            <li>
              <span>2</span>
              <div>
                <strong>We assess your request</strong>
                <p>Priority and response time are set by our support policy.</p>
              </div>
            </li>
            <li>
              <span>3</span>
              <div>
                <strong>Keep your reference</strong>
                <p>Use it whenever you contact us about the same issue.</p>
              </div>
            </li>
          </ol>

          <aside className="customer-privacy-note">
            <strong>Your privacy matters</strong>
            <p>
              Do not include passwords, PINs, full card numbers, or identity-document
              numbers in your message.
            </p>
          </aside>
        </section>

        <section className="customer-form-panel" aria-label="Customer support request">
          {acknowledgement ? (
            <Card className="customer-confirmation" tone="positive">
              <div ref={confirmationRef} tabIndex={-1}>
                <span className="customer-confirmation__icon" aria-hidden="true">✓</span>
                <p className="customer-eyebrow">
                  Request received · {ENVIRONMENTS[environment].label}
                </p>
                <h2>We’re on it, Maria.</h2>
                <p>
                  Your message is safely with our support team. Keep this reference
                  in case you need to contact us again.
                </p>
                <div className="customer-reference">
                  <span>Case reference</span>
                  <strong>{acknowledgement.caseId ?? "Creating case…"}</strong>
                </div>
                {acknowledgement.case && (
                  <KeyValue
                    items={[
                      ["Status", acknowledgement.case.status],
                      ["Priority", acknowledgement.case.priority ?? "Pricing"],
                      [
                        "SLA deadline",
                        acknowledgement.case.slaDeadline
                          ? new Date(acknowledgement.case.slaDeadline).toLocaleString()
                          : "Pricing",
                      ],
                    ]}
                  />
                )}
                {acknowledgement.demo && (
                  <Alert tone="neutral" title="Mock orchestrator demo">
                    This is a real Case in the selected neo-09 environment. The local
                    Customer demo supplied the orchestrator envelope.
                  </Alert>
                )}
                <Button variant="primary" onClick={startAnother}>Send another request</Button>
              </div>
            </Card>
          ) : (
            <Card className="customer-case-card">
              <div className="customer-form-heading">
                <p className="customer-eyebrow">Contact support</p>
                <h2>Submit a request</h2>
                <p>Fields marked with an asterisk are required.</p>
              </div>

              {error && (
                <Alert tone="negative" title="Your request was not sent">
                  {error}
                </Alert>
              )}

              <form onSubmit={submit} noValidate>
                <Field
                  label="What do you need help with?"
                  hint={category ? CATEGORY_HELP[category] : "Choose the closest match."}
                  error={categoryError}
                  required
                  htmlFor="case-category"
                >
                  {({ id, invalid, describedBy }) => (
                    <Select
                      id={id}
                      value={category}
                      onChange={(event) => setCategory(event.target.value)}
                      options={CATEGORY_OPTIONS}
                      placeholder="Select a topic"
                      invalid={invalid}
                      aria-describedby={describedBy}
                      disabled={submitting}
                    />
                  )}
                </Field>

                <Field
                  label="Tell us what happened"
                  hint={`${description.length}/${MAX_DESCRIPTION} characters. Include dates or reference numbers if useful.`}
                  error={descriptionError}
                  required
                  htmlFor="case-description"
                >
                  {({ id, invalid, describedBy }) => (
                    <Textarea
                      id={id}
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      onBlur={() => setTouched(true)}
                      rows={8}
                      maxLength={MAX_DESCRIPTION}
                      placeholder="For example: My card was due to arrive last Friday, but I have not received it and the delivery status has not changed…"
                      invalid={invalid}
                      aria-describedby={describedBy}
                      disabled={submitting}
                    />
                  )}
                </Field>

                <div className="customer-submit-note">
                  <span aria-hidden="true">↗</span>
                  <p>
                    We will link this request to your signed-in profile. Your support
                    team sees only the details needed to help.
                  </p>
                </div>

                <FormActions>
                  <Button type="submit" variant="primary" disabled={submitting}>
                    {submitting ? "Sending securely…" : "Submit request"}
                  </Button>
                </FormActions>
              </form>
            </Card>
          )}
        </section>
      </main>

      <footer className="customer-footer">
        <span>© 2026 Neo Bank</span>
        <span>Secure customer support</span>
      </footer>
    </div>
  );
}
