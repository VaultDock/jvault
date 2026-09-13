import type { FormField } from '../api/types';
import { useT } from '../i18n';
import { LockIcon, SplitIcon, VaultIcon } from './icons';

/**
 * Says where a field's value is going, next to the control that collects it.
 *
 * This is the one piece of the form that has no Jira equivalent, and the reason the form exists
 * at all. A user who learns after submitting that their description went somewhere other than
 * Jira has already decided how much to type into it.
 */
export function PlacementBadge({ field }: { field: FormField }) {
  const { t } = useT();
  if (field.placement === 'JIRA') {
    return null;
  }

  const external = field.placement === 'EXTERNAL';

  return (
    <>
      <span className={external ? 'chip chip--vault' : 'chip chip--both'}>
        {external ? <VaultIcon /> : <SplitIcon />}
        {external ? t.storedInVault : t.bothPlaces}
      </span>
      {field.classification ? (
        <span className="chip chip--class">{field.classification.toLowerCase()}</span>
      ) : null}
    </>
  );
}

/** The chip for a field jvault will not let anyone edit here. */
export function SupportBadge({ field }: { field: FormField }) {
  const { t } = useT();
  if (field.supportLevel !== 'READ_ONLY') {
    return null;
  }
  return (
    <span className="chip chip--muted">
      <LockIcon />
      {t.readOnly}
    </span>
  );
}

/** The sentence under a control, when there is something true worth saying. */
export function FieldHint({ field }: { field: FormField }) {
  const { t } = useT();
  const lines: string[] = [];

  if (field.placement === 'EXTERNAL') {
    lines.push(t.hintExternal);
  } else if (field.placement === 'BOTH') {
    lines.push(t.hintBoth);
  }

  if (field.supportLevel === 'READ_ONLY') {
    lines.push(t.hintReadOnly);
  } else if (field.supportLevel === 'DELEGATED_VALIDATION') {
    lines.push(t.hintDelegated);
  }

  if (field.hasMoreOptions) {
    // Truncated rather than complete, and saying so beats a list that quietly omits the value
    // someone is looking for.
    lines.push(t.hintTruncated);
  }

  if (lines.length === 0) {
    return null;
  }
  return <span className="field__hint">{lines.join(' ')}</span>;
}
