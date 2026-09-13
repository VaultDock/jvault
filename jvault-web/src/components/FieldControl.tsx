import type { FormField } from '../api/types';
import { PlacementBadge, SupportNotice } from './PlacementBadge';
import { RichTextField } from './RichTextField';

/**
 * One field of the create form.
 *
 * Which control appears is decided by the server, from the same table that encodes the value on
 * the way to Jira. A date picker feeding a field Jira reads as a user account is the kind of
 * mismatch that shows up only as a rejection at submit time.
 */
export function FieldControl({
  field,
  value,
  error,
  onChange,
}: {
  field: FormField;
  value: string;
  error?: string | undefined;
  onChange: (value: string) => void;
}) {
  const editable = field.supportLevel !== 'READ_ONLY';
  const id = `field-${field.key}`;

  return (
    <div className={`field${error ? ' field--error' : ''}`}>
      <label htmlFor={id}>
        {field.name}
        {field.required ? <span className="field__required" aria-label="required"> *</span> : null}
      </label>

      <div className="field__annotations">
        <PlacementBadge field={field} />
        <SupportNotice field={field} />
      </div>

      {renderControl(field, id, value, editable, onChange)}

      {field.hasMoreOptions ? (
        // Truncated rather than complete, and saying so beats a list that quietly omits the
        // value someone is looking for.
        <span className="notice">
          Showing the first options only. Type the exact value if it is not listed.
        </span>
      ) : null}

      {error ? (
        <span className="field__errorText" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

function renderControl(
  field: FormField,
  id: string,
  value: string,
  editable: boolean,
  onChange: (value: string) => void,
) {
  const common = {
    id,
    disabled: !editable,
    'aria-required': field.required,
    'aria-invalid': undefined,
  };

  switch (field.control) {
    case 'RICH_TEXT':
      return <RichTextField value={value} onChange={onChange} editable={editable} />;

    case 'SELECT':
    case 'MULTI_SELECT':
      return (
        <select
          {...common}
          value={value}
          multiple={field.control === 'MULTI_SELECT'}
          onChange={(event) =>
            onChange(
              field.control === 'MULTI_SELECT'
                ? Array.from(event.target.selectedOptions, (option) => option.value).join(',')
                : event.target.value,
            )
          }
        >
          <option value="">—</option>
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
          placeholder="Comma separated"
          onChange={(event) => onChange(event.target.value)}
        />
      );

    case 'USER':
      return (
        <input
          {...common}
          type="text"
          value={value}
          // A picker needs a user search endpoint, which jvault does not proxy yet. An account id
          // that works is better than a name picker that does not.
          placeholder="Account id"
          onChange={(event) => onChange(event.target.value)}
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
