import { useMemo, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { FormDefinition, FormField, TicketResponse } from '../api/types';
import { useT } from '../i18n';
import { FieldControl } from './FieldControl';
import { AlertIcon, CheckIcon, VaultIcon } from './icons';

/** Answered by the context bar above the form, and carried in the request body. */
const DECIDED_ABOVE = new Set(['project', 'issuetype']);

type Status =
  | { kind: 'editing' }
  | { kind: 'submitting' }
  | { kind: 'created'; ticket: TicketResponse }
  | { kind: 'failed'; message: string };

export function CreateIssueForm({
  definition,
  deploymentId,
}: {
  definition: FormDefinition;
  deploymentId: string;
}) {
  const { t } = useT();
  const [values, setValues] = useState<Record<string, string>>({});
  const [fieldErrors, setFieldErrors] = useState<Map<string, string>>(new Map());
  const [status, setStatus] = useState<Status>({ kind: 'editing' });

  // One key per attempt, reused if the attempt is retried. A user pressing Create twice on
  // purpose means it; a browser retrying a dropped connection does not, and only one of those
  // should produce a second issue.
  const [attempt, setAttempt] = useState(() => crypto.randomUUID());

  // Project and issue type are chosen above the form and travel in the request itself. Asking
  // for them again as required fields makes the form look broken to anyone who already answered.
  const shown = useMemo(
    () => definition.fields.filter((field) => !DECIDED_ABOVE.has(field.key)),
    [definition],
  );
  const editable = useMemo(
    () => shown.filter((field) => field.supportLevel !== 'READ_ONLY'),
    [shown],
  );
  const external = useMemo(
    () => shown.filter((field) => field.placement !== 'JIRA'),
    [shown],
  );

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setStatus({ kind: 'submitting' });
    setFieldErrors(new Map());

    try {
      const ticket = await api.createTicket(
        {
          deploymentId,
          projectKey: definition.projectKey,
          issueTypeId: definition.issueTypeId,
          fields: filled(editable, values),
        },
        attempt,
      );
      setStatus({ kind: 'created', ticket });
    } catch (error) {
      if (error instanceof ApiError) {
        const byField = error.fieldErrors();
        setFieldErrors(byField);
        // A rejected request created nothing, so the next attempt is a new one.
        setAttempt(crypto.randomUUID());
        setStatus({
          kind: 'failed',
          message: byField.size > 0 ? t.errorFields : error.message,
        });
        return;
      }
      // A dropped connection. The ticket may well have been created, so the key is kept: if it
      // was, retrying returns that ticket rather than making a second one.
      setStatus({
        kind: 'failed',
        message: t.errorRetrySafe,
      });
    }
  }

  if (status.kind === 'created') {
    return (
      <CreatedTicket
        ticket={status.ticket}
        onReset={() => {
          setValues({});
          setAttempt(crypto.randomUUID());
          setStatus({ kind: 'editing' });
        }}
      />
    );
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-body">
        {external.length > 0 ? (
          <p className="notice notice--vault">
            <VaultIcon />
            <span>
              <strong>
                {external.length === 1 ? t.vaultNoticeOne : t.vaultNoticeMany(external.length)}
              </strong>{' '}
              {t.vaultNoticeTail}
            </span>
          </p>
        ) : null}

        {status.kind === 'failed' ? (
          <p className="notice notice--error" role="alert">
            <AlertIcon />
            <span>{status.message}</span>
          </p>
        ) : null}

        {shown.map((field) => (
          <FieldControl
            key={field.key}
            field={field}
            projectKey={definition.projectKey}
            value={values[field.key] ?? ''}
            error={fieldErrors.get(field.key)}
            onChange={(value) => setValues((current) => ({ ...current, [field.key]: value }))}
          />
        ))}
      </div>

      <div className="actions">
        <button type="submit" className="btn-primary" disabled={status.kind === 'submitting'}>
          {status.kind === 'submitting' ? t.creating : t.create}
        </button>
        <span className="actions__note">
          {t.fieldCount(shown.length, external.length)}
        </span>
      </div>
    </form>
  );
}

/** Only fields the user actually filled in; an empty string is not an instruction to clear. */
function filled(fields: FormField[], values: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const field of fields) {
    const value = values[field.key];
    if (value !== undefined && value !== '') {
      result[field.key] = value;
    }
  }
  return result;
}

function CreatedTicket({ ticket, onReset }: { ticket: TicketResponse; onReset: () => void }) {
  const { t } = useT();
  return (
    <div className="result">
      <div className="result__head">
        <span className="result__tick">
          <CheckIcon />
        </span>
        <h2>{t.created}</h2>
      </div>

      <dl>
        <dt>{t.ticket}</dt>
        <dd>{ticket.ticketRef}</dd>
        <dt>{t.state}</dt>
        {/* A ticket can exist before its Jira issue does: the write is dispatched from the
            outbox, so "accepted, not yet in Jira" is a real and ordinary state to be in. */}
        <dd>
          <span className="pill">{ticket.state}</span>
        </dd>
        <dt>{t.jiraIssue}</dt>
        <dd>{ticket.issueKey ?? t.beingCreated}</dd>
      </dl>

      {ticket.parts.length > 0 ? (
        <>
          <h3>{t.heldInVault}</h3>
          <ul className="parts">
            {ticket.parts.map((part) => (
              <li key={part.contentRef}>
                <VaultIcon />
                <span className="parts__name">{part.fieldKey ?? part.partType}</span>
                <span className="chip chip--class">{part.classification.toLowerCase()}</span>
                {/* Not a capability. Following it is authorized afresh, so a link that reaches
                    the wrong person still shows them nothing. */}
                <a href={part.link}>{t.open}</a>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <button type="button" className="btn-secondary" onClick={onReset}>
        {t.createAnother}
      </button>
    </div>
  );
}
