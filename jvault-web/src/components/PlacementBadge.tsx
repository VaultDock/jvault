import type { FormField } from '../api/types';

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

  const label = field.placement === 'EXTERNAL' ? 'Stored in jvault' : 'Jira and jvault';
  const detail =
    field.placement === 'EXTERNAL'
      ? 'Jira will show a link, not this text.'
      : 'This text goes to Jira as well as jvault.';

  return (
    <span className={`badge badge--${field.placement.toLowerCase()}`}>
      <strong>{label}</strong>
      {field.classification ? ` · ${field.classification.toLowerCase()}` : ''}
      <span className="badge__detail"> {detail}</span>
    </span>
  );
}

export function SupportNotice({ field }: { field: FormField }) {
  if (field.supportLevel === 'REPRODUCED') {
    return null;
  }
  if (field.supportLevel === 'READ_ONLY') {
    return (
      <span className="notice">
        Not editable here — open the issue in Jira to change it.
      </span>
    );
  }
  return <span className="notice">Jira validates this field when the ticket is submitted.</span>;
}
