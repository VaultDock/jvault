import { useRef, useState } from 'react';
import type { Placement } from '../api/types';
import { useT } from '../i18n';
import { LockIcon, VaultIcon } from './icons';

export interface PendingFile {
  id: string;
  file: File;
  /** Set once the ticket exists and the upload has been attempted. */
  status: 'pending' | 'uploading' | 'done' | 'failed';
  error?: string;
}

/**
 * Documents to attach.
 *
 * <p>Chosen here and uploaded after the ticket exists, because an attachment needs a ticket to
 * belong to. Doing it the other way round — make the user create the ticket, then find it again
 * to attach the file they already had in hand — is two jobs where there was one.
 *
 * <p>Nothing is uploaded while the form is being filled in. A file sent before the user presses
 * Create is a file jvault has to explain the existence of if they never press it.
 */
export function AttachmentField({
  placement,
  classification,
  files,
  onChange,
  disabled,
  maxBytes,
}: {
  /** Where policy puts attachments for this project — asked, never assumed. */
  placement: Placement;
  classification: string | null;
  files: PendingFile[];
  onChange: (files: PendingFile[]) => void;
  disabled: boolean;
  maxBytes: number;
}) {
  const { t } = useT();
  // Jira-placed attachments would have to be uploaded to Jira, and that path is not built: the
  // API answers 501 rather than quietly keeping a copy policy never asked for. Saying so before
  // someone picks a file beats saying so after.
  const supported = placement !== 'JIRA';
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const locked = disabled || !supported;

  function add(chosen: FileList | null) {
    if (!chosen) {
      return;
    }
    const added: PendingFile[] = [];
    for (const file of Array.from(chosen)) {
      added.push({
        id: `${file.name}:${file.size}:${file.lastModified}`,
        file,
        status: 'pending',
        // Checked here as a courtesy; the server checks it as a rule.
        ...(file.size > maxBytes ? { status: 'failed' as const, error: t.fileTooLarge } : {}),
      });
    }
    // Same file picked twice is one attachment, not two.
    const byId = new Map(files.map((existing) => [existing.id, existing]));
    for (const file of added) {
      byId.set(file.id, file);
    }
    onChange([...byId.values()]);
  }

  return (
    <div className={`field${supported ? ' field--external' : ''}`}>
      <div className="field__head">
        <span className="field__label">{t.attachments}</span>
        {supported ? (
          <>
            <span className="chip chip--vault">
              <VaultIcon />
              {t.storedInVault}
            </span>
            {classification ? (
              <span className="chip chip--class">{classification.toLowerCase()}</span>
            ) : null}
          </>
        ) : (
          <span className="chip chip--muted">
            <LockIcon />
            {t.readOnly}
          </span>
        )}
      </div>

      <div
        className={`dropzone${dragging ? ' is-dragging' : ''}${locked ? ' is-disabled' : ''}`}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          if (!locked) {
            add(event.dataTransfer.files);
          }
        }}
      >
        <input
          ref={input}
          type="file"
          multiple
          data-testid="attachment-input"
          disabled={locked}
          onChange={(event) => {
            add(event.target.files);
            // So that choosing the same file again after removing it still fires a change.
            event.target.value = '';
          }}
        />
        <button
          type="button"
          className="btn-secondary"
          disabled={locked}
          onClick={() => input.current?.click()}
        >
          {t.chooseFiles}
        </button>
        <span className="dropzone__hint">{supported ? t.dropHere : t.attachmentsToJira}</span>
      </div>

      {files.length > 0 ? (
        <ul className="filelist">
          {files.map((pending) => (
            <li key={pending.id} className={`filelist__item is-${pending.status}`}>
              <VaultIcon />
              <span className="filelist__name">{pending.file.name}</span>
              <span className="filelist__size">{humanSize(pending.file.size)}</span>
              <span className="filelist__status">{statusLabel(pending, t)}</span>
              {pending.status === 'pending' || pending.status === 'failed' ? (
                <button
                  type="button"
                  className="picker__clear"
                  aria-label={t.remove}
                  onClick={() => onChange(files.filter((other) => other.id !== pending.id))}
                >
                  ×
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      <span className="field__hint">
        {supported ? t.attachmentsHint : t.attachmentsToJiraHint}
      </span>
    </div>
  );
}

function statusLabel(pending: PendingFile, t: ReturnType<typeof useT>['t']): string {
  switch (pending.status) {
    case 'uploading':
      return t.uploading;
    case 'done':
      return t.uploaded;
    case 'failed':
      return pending.error ?? t.uploadFailed;
    default:
      return '';
  }
}

/** Bytes are for machines. */
function humanSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}
