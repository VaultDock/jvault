import { useMemo, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { FormDefinition, FormField, TicketResponse } from '../api/types';
import { FieldControl } from './FieldControl';

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
  const [values, setValues] = useState<Record<string, string>>({});
  const [fieldErrors, setFieldErrors] = useState<Map<string, string>>(new Map());
  const [status, setStatus] = useState<Status>({ kind: 'editing' });

  // One key per attempt, reused if the attempt is retried. A user pressing Create twice on
  // purpose means it; a browser retrying a dropped connection does not, and only one of those
  // should produce a second issue.
  const [attempt, setAttempt] = useState(() => crypto.randomUUID());

  const editable = useMemo(
    () => definition.fields.filter((field) => field.supportLevel !== 'READ_ONLY'),
    [definition],
  );
  const external = useMemo(
    () => definition.fields.filter((field) => field.placement !== 'JIRA'),
    [definition],
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
          message: byField.size > 0 ? 'Some fields need attention.' : error.message,
        });
        return;
      }
      // A dropped connection. The ticket may well have been created, so the key is kept: if it
      // was, retrying returns that ticket rather than making a second one.
      setStatus({
        kind: 'failed',
        message: 'Could not reach jvault. Retrying will not create a duplicate.',
      });
    }
  }

  if (status.kind === 'created') {
    return <CreatedTicket ticket={status.ticket} onReset={() => {
      setValues({});
      setAttempt(crypto.randomUUID());
      setStatus({ kind: 'editing' });
    }} />;
  }

  return (
    <form onSubmit={submit} noValidate>
      {external.length > 0 ? (
        <p className="summaryNotice">
          {external.length === 1 ? 'One field on this form is' : `${external.length} fields on this form are`}{' '}
          stored in jvault rather than in Jira. Each one is marked below.
        </p>
      ) : null}

      {definition.fields.map((field) => (
        <FieldControl
          key={field.key}
          field={field}
          value={values[field.key] ?? ''}
          error={fieldErrors.get(field.key)}
          onChange={(value) => setValues((current) => ({ ...current, [field.key]: value }))}
        />
      ))}

      {status.kind === 'failed' ? (
        <p className="formError" role="alert">
          {status.message}
        </p>
      ) : null}

      <button type="submit" disabled={status.kind === 'submitting'}>
        {status.kind === 'submitting' ? 'Creating…' : 'Create'}
      </button>
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
  return (
    <div className="created">
      <h2>Created</h2>
      <dl>
        <dt>Ticket</dt>
        <dd>{ticket.ticketRef}</dd>
        <dt>State</dt>
        {/* A ticket can exist before its Jira issue does: the write is dispatched from the
            outbox, so "accepted, not yet in Jira" is a real and ordinary state to be in. */}
        <dd>{ticket.state}</dd>
        <dt>Jira issue</dt>
        <dd>{ticket.issueKey ?? 'being created'}</dd>
      </dl>

      {ticket.parts.length > 0 ? (
        <>
          <h3>Held in jvault</h3>
          <ul>
            {ticket.parts.map((part) => (
              <li key={part.contentRef}>
                {part.fieldKey ?? part.partType} · {part.classification.toLowerCase()}{' '}
                {/* Not a capability. Following it is authorized afresh, so a link that reaches
                    the wrong person still shows them nothing. */}
                <a href={part.link}>open</a>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <button type="button" onClick={onReset}>
        Create another
      </button>
    </div>
  );
}
