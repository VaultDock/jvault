import type { FormField } from '../api/types';
import { useT } from '../i18n';
import { FieldHint, PlacementBadge, SupportBadge } from './PlacementBadge';
import { RichTextField } from './RichTextField';
import { IssuePicker } from './IssuePicker';
import { UserPicker } from './UserPicker';

/**
 * One field of the create form.
 *
 * Which control appears is decided by the server, from the same table that encodes the value on
 * the way to Jira. A date picker feeding a field Jira reads as a user account is the kind of
 * mismatch that shows up only as a rejection at submit time.
 */
export function FieldControl({
  field,
  projectKey,
  value,
  error,
  onChange,
}: {
  field: FormField;
  projectKey: string;
  value: string;
  error?: string | undefined;
  onChange: (value: string) => void;
}) {
  const { t } = useT();
  const editable = field.supportLevel !== 'READ_ONLY';
  const id = `field-${field.key}`;

  const classes = ['field'];
  if (field.placement !== 'JIRA') {
    classes.push('field--external');
  }
  if (error) {
    classes.push('field--invalid');
  }

  return (
    <div className={classes.join(' ')}>
      <div className="field__head">
        <label className="field__label" htmlFor={id}>
          {field.name}
          {field.required ? (
            <span className="field__required" aria-label={t.required}>
              *
            </span>
          ) : null}
        </label>
        <PlacementBadge field={field} />
        <SupportBadge field={field} />
      </div>

      {renderControl(field, projectKey, id, value, editable, onChange, t)}

      <FieldHint field={field} />

      {error ? (
        <span className="field__error" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/** The wire form is one comma-separated string; the control wants the pieces. */
function splitValues(value: string): string[] {
  return value === '' ? [] : value.split(',');
}

function renderControl(
  field: FormField,
  projectKey: string,
  id: string,
  value: string,
  editable: boolean,
  onChange: (value: string) => void,
  t: ReturnType<typeof useT>['t'],
) {
  const common = {
    id,
    disabled: !editable,
    'aria-required': field.required,
    'aria-invalid': undefined,
  };

  switch (field.control) {
    case 'RICH_TEXT':
      return (
        <RichTextField value={value} onChange={onChange} editable={editable} id={id} />
      );

    case 'SELECT':
    case 'MULTI_SELECT':
      return (
        <select
          {...common}
          // A multiple select is controlled by an array. Handed a string it silently shows
          // nothing selected, which looks like the options failing to load.
          value={field.control === 'MULTI_SELECT' ? splitValues(value) : value}
          multiple={field.control === 'MULTI_SELECT'}
          onChange={(event) =>
            onChange(
              field.control === 'MULTI_SELECT'
                ? Array.from(event.target.selectedOptions, (option) => option.value).join(',')
                : event.target.value,
            )
          }
        >
          {field.control === 'MULTI_SELECT' ? null : <option value="">{t.choose}</option>}
          {field.allowedValues.map((option) => (
            <option key={option.id} value={option.id}>
              {option.value}
            </option>
          ))}
        </select>
      );

    case 'DATE':
      return (
        <input
          {...common}
          type="date"
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      );

    case 'NUMBER':
      return (
        <input
          {...common}
          type="number"
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      );

    case 'LABELS':
      return (
        <input
          {...common}
          type="text"
          value={value}
          placeholder={t.commaSeparated}
          onChange={(event) => onChange(event.target.value)}
        />
      );

    case 'ISSUE':
      return (
        <IssuePicker
          id={id}
          projectKey={projectKey}
          value={value}
          disabled={!editable}
          required={field.required}
          onChange={onChange}
        />
      );

    case 'USER':
      return (
        <UserPicker
          id={id}
          projectKey={projectKey}
          // Only an assignee has to be assignable. A reporter can be anyone with an account, and
          // narrowing that list would hide the person who actually reported it.
          assignable={field.key === 'assignee'}
          value={value}
          disabled={!editable}
          required={field.required}
          onChange={onChange}
        />
      );

    default:
      return (
        <input
          {...common}
          type="text"
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      );
  }
}
