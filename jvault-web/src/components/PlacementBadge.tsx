import type { FormField } from '../api/types';
import { LockIcon, SplitIcon, VaultIcon } from './icons';

/**
 * Says where a field's value is going, next to the control that collects it.
 *
 * This is the one piece of the form that has no Jira equivalent, and the reason the form exists
 * at all. A user who learns after submitting that their description went somewhere other than
 * Jira has already decided how much to type into it.
 */
export function PlacementBadge({ field }: { field: FormField }) {
  if (field.placement === 'JIRA') {
    return null;
  }

  const external = field.placement === 'EXTERNAL';

  return (
    <>
      <span className={external ? 'chip chip--vault' : 'chip chip--both'}>
        {external ? <VaultIcon /> : <SplitIcon />}
        {external ? 'Stored in jvault' : 'Jira and jvault'}
      </span>
      {field.classification ? (
        <span className="chip chip--class">{field.classification.toLowerCase()}</span>
      ) : null}
    </>
  );
}

/** The chip for a field jvault will not let anyone edit here. */
export function SupportBadge({ field }: { field: FormField }) {
  if (field.supportLevel !== 'READ_ONLY') {
    return null;
  }
  return (
    <span className="chip chip--muted">
      <LockIcon />
      Read-only
    </span>
  );
}

/** The sentence under a control, when there is something true worth saying. */
export function FieldHint({ field }: { field: FormField }) {
  const lines: string[] = [];

  if (field.placement === 'EXTERNAL') {
    lines.push('Jira will show a link here, not this text.');
  } else if (field.placement === 'BOTH') {
    lines.push('This text goes to Jira as well as jvault.');
  }

  if (field.supportLevel === 'READ_ONLY') {
    lines.push('Not editable here — open the issue in Jira to change it.');
  } else if (field.supportLevel === 'DELEGATED_VALIDATION') {
    lines.push('Jira validates this field when the ticket is submitted.');
  }

  if (field.hasMoreOptions) {
    // Truncated rather than complete, and saying so beats a list that quietly omits the value
    // someone is looking for.
    lines.push('Showing the first options only. Type the exact value if it is not listed.');
  }

  if (lines.length === 0) {
    return null;
  }
  return <span className="field__hint">{lines.join(' ')}</span>;
}
