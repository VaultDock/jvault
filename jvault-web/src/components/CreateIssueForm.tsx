import { useEffect, useMemo, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { FormDefinition, FormField, TicketResponse } from '../api/types';
import { useJiraIssueUrl, useT } from '../i18n';
import { AttachmentField, type PendingFile } from './AttachmentField';
import { FieldControl } from './FieldControl';
import { AlertIcon, CheckIcon, VaultIcon } from './icons';

/**
 * Not rendered among the ordinary fields: project and issue type are answered by the context bar
 * above, and attachments have a control of their own.
 */
const DECIDED_ABOVE = new Set(['project', 'issuetype', 'attachment']);

/** A file above this is refused by the server too; checking here only saves the round trip. */
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

type Status =
  | { kind: 'editing' }
  | { kind: 'submitting' }
  | { kind: 'uploading' }
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
  const [files, setFiles] = useState<PendingFile[]>([]);

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
  // Attachments are described by Jira's own `attachment` field, which the form renders
  // read-only. Its placement is what decides whether documents go to the vault or to Jira.
  const attachmentField = useMemo(
    () => definition.fields.find((field) => field.key === 'attachment'),
    [definition],
  );
  const external = useMemo(
    () => shown.filter((field) => field.placement !== 'JIRA'),
    [shown],
  );
  const vaultedCount =
    external.length + (attachmentField && attachmentField.placement !== 'JIRA' ? 1 : 0);

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
      // The ticket exists now, so the attachments have something to belong to. A file that
      // fails here does not undo the ticket: reporting both outcomes is more use than pretending
      // the whole thing failed.
      if (files.length > 0) {
        setStatus({ kind: 'uploading' });
        await uploadAll(ticket.ticketRef);
      }
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

  async function uploadAll(ticketRef: string) {
    const queue = files.filter((pending) => pending.status !== 'done');

    for (const pending of queue) {
      if (pending.file.size > MAX_UPLOAD_BYTES) {
        continue;
      }
      mark(pending.id, { status: 'uploading' });
      try {
        await api.uploadAttachment(ticketRef, pending.file);
        mark(pending.id, { status: 'done' });
      } catch (error) {
        mark(pending.id, {
          status: 'failed',
          error: error instanceof ApiError ? error.message : t.uploadFailed,
        });
      }
    }
  }

  function mark(id: string, change: Partial<PendingFile>) {
    setFiles((current) =>
      current.map((pending) => (pending.id === id ? { ...pending, ...change } : pending)),
    );
  }

  if (status.kind === 'created') {
    return (
      <CreatedTicket
        ticket={status.ticket}
        files={files}
        onRetryUploads={() => uploadAll(status.ticket.ticketRef)}
        onReset={() => {
          setValues({});
          setFiles([]);
          setAttempt(crypto.randomUUID());
          setStatus({ kind: 'editing' });
        }}
      />
    );
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-body">
        {vaultedCount > 0 ? (
          <p className="notice notice--vault">
            <VaultIcon />
            <span>
              <strong>
                {vaultedCount === 1 ? t.vaultNoticeOne : t.vaultNoticeMany(vaultedCount)}
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

        <AttachmentField
          placement={attachmentField?.placement ?? 'EXTERNAL'}
          classification={attachmentField?.classification ?? null}
          files={files}
          onChange={setFiles}
          disabled={status.kind === 'submitting' || status.kind === 'uploading'}
          maxBytes={MAX_UPLOAD_BYTES}
        />

        {shown.map((field) => (
          <FieldControl
            key={field.key}
            field={field}
            projectKey={definition.projectKey}
            issueTypeId={definition.issueTypeId}
            value={values[field.key] ?? ''}
            error={fieldErrors.get(field.key)}
            onChange={(value) => setValues((current) => ({ ...current, [field.key]: value }))}
          />
        ))}
      </div>

      <div className="actions">
        <button
          type="submit"
          className="btn-primary"
          disabled={status.kind === 'submitting' || status.kind === 'uploading'}
        >
          {status.kind === 'submitting'
            ? t.creating
            : status.kind === 'uploading'
              ? t.uploading
              : t.create}
        </button>
        <span className="actions__note">
          {t.fieldCount(shown.length, vaultedCount)}
        </span>
      </div>
    </form>
  );
}

/** The way back to Jira, once there is an issue to go back to. */
function JiraLink({ issueKey }: { issueKey: string | null }) {
  const { t } = useT();
  const url = useJiraIssueUrl(issueKey);
  if (!url) {
    return null;
  }
  return (
    <a className="result__jira" href={url} target="_blank" rel="noreferrer noopener">
      {t.openInJira}
    </a>
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

function CreatedTicket({
  ticket,
  files,
  onRetryUploads,
  onReset,
}: {
  ticket: TicketResponse;
  files: PendingFile[];
  onRetryUploads: () => void;
  onReset: () => void;
}) {
  const { t } = useT();
  const failed = files.filter((pending) => pending.status === 'failed');

  // The Jira issue is created from the outbox a moment after the ticket, so the response that
  // gets us here never carries an issue key. Without this the panel says "being created" until
  // somebody thinks to go and look, which is the opposite of telling them the number.
  const [settled, setSettled] = useState(ticket);

  useEffect(() => {
    if (settled.issueKey || settled.state === 'FAILED') {
      return;
    }
    const timer = setInterval(() => {
      api
        .ticket(ticket.ticketRef)
        .then((latest) => setSettled(latest))
        // A poll that fails changes nothing: the ticket exists either way, and the next tick
        // will ask again.
        .catch(() => undefined);
    }, 1500);
    return () => clearInterval(timer);
  }, [ticket.ticketRef, settled.issueKey, settled.state]);
  return (
    <div className="result">
      <div className="result__head">
        <span className={`result__tick${settled.state === 'FAILED' ? ' is-failed' : ''}`}>
          {settled.state === 'FAILED' ? <AlertIcon /> : <CheckIcon />}
        </span>
        {/* The number, as large as the word. It is what somebody came here to be told. */}
        <h2>{settled.issueKey ?? (settled.state === 'FAILED' ? t.notInJira : t.created)}</h2>
        {settled.issueKey ? <span className="result__sub">{t.created}</span> : null}
        <JiraLink issueKey={settled.issueKey} />
      </div>

      {!settled.issueKey && settled.state !== 'FAILED' ? (
        <p className="result__waiting">
          <span className="spinner" aria-hidden="true" />
          {t.beingCreated}
        </p>
      ) : null}

      {settled.state === 'FAILED' ? (
        <p className="notice notice--error" role="alert">
          <AlertIcon />
          <span>{t.jiraRejected}</span>
        </p>
      ) : null}

      <dl>
        <dt>{t.ticket}</dt>
        <dd>{settled.ticketRef}</dd>
        <dt>{t.state}</dt>
        {/* A ticket can exist before its Jira issue does: the write is dispatched from the
            outbox, so "accepted, not yet in Jira" is a real and ordinary state to be in. */}
        <dd>
          <span className="pill">{settled.state}</span>
        </dd>
        <dt>{t.ticketView}</dt>
        <dd>
          <a href={`/t/${settled.ticketRef}`}>{t.openTicketView}</a>
        </dd>
      </dl>

      {failed.length > 0 ? (
        <p className="notice notice--error" role="alert">
          <AlertIcon />
          <span>
            {t.someFilesFailed}{' '}
            <button type="button" className="linklike" onClick={onRetryUploads}>
              {t.retryUploads}
            </button>
          </span>
        </p>
      ) : null}

      {files.some((pending) => pending.status === 'done') ? (
        <>
          <h3>{t.attachments}</h3>
          <ul className="parts">
            {files
              .filter((pending) => pending.status === 'done')
              .map((pending) => (
                <li key={pending.id}>
                  <VaultIcon />
                  <span className="parts__name">{pending.file.name}</span>
                  <span className="chip chip--class">{t.uploaded}</span>
                </li>
              ))}
          </ul>
        </>
      ) : null}

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
