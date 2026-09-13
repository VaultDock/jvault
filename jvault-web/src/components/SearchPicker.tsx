import { useEffect, useRef, useState } from 'react';
import { useT } from '../i18n';

export interface Choice {
  /** What gets sent to Jira: an account id, an issue key. */
  value: string;
  label: string;
  /** Shown beside the label — an email, an issue type. Never the thing being matched on. */
  meta?: string | null;
  badge?: string | null;
}

/**
 * Choose one thing from a list the server searches.
 *
 * <p>Shared by the people picker and the parent picker because they are the same control: type a
 * few letters, see candidates, send an identifier the person never has to know. Only the search
 * differs, and that is the argument.
 *
 * <p>The search runs on the server against Jira — the browser is not allowed to ask Jira
 * anything, which is the whole arrangement — and is debounced, because Jira rate-limits per
 * account and a keystroke is a round trip.
 */
export function SearchPicker({
  id,
  value,
  disabled,
  required,
  placeholder,
  search,
  describe,
  onChange,
}: {
  id: string;
  value: string;
  disabled: boolean;
  required: boolean;
  placeholder: string;
  search: (query: string) => Promise<Choice[]>;
  /** Renders the badge for a chosen item; initials for a person, nothing for an issue. */
  describe?: ((choice: Choice) => string) | undefined;
  onChange: (value: string) => void;
}) {
  const { t } = useT();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Choice[]>([]);
  const [chosen, setChosen] = useState<Choice | null>(null);
  const [open, setOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const [highlighted, setHighlighted] = useState(0);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const timer = setTimeout(() => {
      setSearching(true);
      search(query)
        .then((found) => {
          setResults(found);
          setHighlighted(0);
        })
        .catch(() => setResults([]))
        .finally(() => setSearching(false));
    }, 220);
    return () => clearTimeout(timer);
    // `search` is rebuilt on every render by its caller; depending on it would re-query forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, open]);

  useEffect(() => {
    function onDocumentClick(event: MouseEvent) {
      if (box.current && !box.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onDocumentClick);
    return () => document.removeEventListener('mousedown', onDocumentClick);
  }, []);

  // The form was reset; what is shown must go with it.
  useEffect(() => {
    if (value === '') {
      setChosen(null);
      setQuery('');
    }
  }, [value]);

  function choose(choice: Choice) {
    setChosen(choice);
    setQuery('');
    setOpen(false);
    onChange(choice.value);
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      setHighlighted((current) => {
        const next = event.key === 'ArrowDown' ? current + 1 : current - 1;
        return Math.max(0, Math.min(results.length - 1, next));
      });
    } else if (event.key === 'Enter' && open) {
      const choice = results[highlighted];
      if (choice) {
        event.preventDefault();
        choose(choice);
      }
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  }

  if (chosen) {
    return (
      <div className="picker" ref={box}>
        <div className="picker__chosen">
          <Badge text={chosen.badge ?? describe?.(chosen) ?? ''} />
          <span className="picker__name">{chosen.label}</span>
          {chosen.meta ? <span className="picker__meta">{chosen.meta}</span> : null}
          {disabled ? null : (
            <button
              type="button"
              className="picker__clear"
              aria-label={t.clear}
              onClick={() => {
                setChosen(null);
                onChange('');
              }}
            >
              ×
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="picker" ref={box}>
      <input
        id={id}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        aria-controls={`${id}-listbox`}
        aria-required={required}
        autoComplete="off"
        disabled={disabled}
        placeholder={placeholder}
        value={query}
        onFocus={() => setOpen(true)}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
        onKeyDown={onKeyDown}
      />

      {open && !disabled ? (
        <ul className="picker__list" id={`${id}-listbox`} role="listbox">
          {results.map((choice, index) => (
            <li
              key={choice.value}
              role="option"
              aria-selected={index === highlighted}
              className={index === highlighted ? 'is-highlighted' : ''}
              onMouseEnter={() => setHighlighted(index)}
              onMouseDown={(event) => {
                // mousedown, not click: the input's blur would close the list first.
                event.preventDefault();
                choose(choice);
              }}
            >
              <Badge text={choice.badge ?? describe?.(choice) ?? ''} />
              <span className="picker__name">{choice.label}</span>
              {choice.meta ? <span className="picker__meta">{choice.meta}</span> : null}
            </li>
          ))}
          {results.length === 0 ? (
            <li className="picker__empty">{searching ? t.searching : t.noMatches}</li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}

/** Initials or a key prefix, rather than a fetched avatar: a picture per row is a request per row. */
function Badge({ text }: { text: string }) {
  if (text === '') {
    return null;
  }
  return (
    <span className="avatar" aria-hidden="true">
      {text}
    </span>
  );
}

export function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
}
