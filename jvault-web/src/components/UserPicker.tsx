import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { UserRef } from '../api/types';
import { useT } from '../i18n';

/**
 * A person, chosen by name.
 *
 * Jira accepts an account id and nothing else, and an account id is the last thing anyone knows
 * about a colleague. The previous control was a text box labelled "Account id", which is not so
 * much a control as an obstacle.
 *
 * The search runs on the server against Jira, because the list of people who can be assigned
 * work in a project is a Jira question and the browser is not allowed to ask Jira anything.
 */
export function UserPicker({
  id,
  projectKey,
  assignable,
  value,
  disabled,
  required,
  onChange,
}: {
  id: string;
  projectKey: string;
  assignable: boolean;
  value: string;
  disabled: boolean;
  required: boolean;
  onChange: (accountId: string) => void;
}) {
  const { t } = useT();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserRef[]>([]);
  const [chosen, setChosen] = useState<UserRef | null>(null);
  const [open, setOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const [highlighted, setHighlighted] = useState(0);
  const box = useRef<HTMLDivElement>(null);

  // Debounced, because every keystroke is a round trip to Jira and Jira rate-limits per account.
  useEffect(() => {
    if (!open) {
      return;
    }
    const timer = setTimeout(() => {
      setSearching(true);
      api
        .searchUsers(projectKey, query, assignable)
        .then((found) => {
          setResults(found);
          setHighlighted(0);
        })
        .catch(() => setResults([]))
        .finally(() => setSearching(false));
    }, 220);
    return () => clearTimeout(timer);
  }, [query, open, projectKey, assignable]);

  useEffect(() => {
    function onDocumentClick(event: MouseEvent) {
      if (box.current && !box.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onDocumentClick);
    return () => document.removeEventListener('mousedown', onDocumentClick);
  }, []);

  // The form was reset; the name shown must go with it.
  useEffect(() => {
    if (value === '') {
      setChosen(null);
      setQuery('');
    }
  }, [value]);

  function choose(user: UserRef) {
    setChosen(user);
    setQuery('');
    setOpen(false);
    onChange(user.accountId);
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
      const user = results[highlighted];
      if (user) {
        event.preventDefault();
        choose(user);
      }
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  }

  if (chosen) {
    return (
      <div className="picker" ref={box}>
        <div className="picker__chosen">
          <Avatar name={chosen.displayName} />
          <span className="picker__name">{chosen.displayName}</span>
          {chosen.email ? <span className="picker__meta">{chosen.email}</span> : null}
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
        placeholder={t.searchPeople}
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
          {results.map((user, index) => (
            <li
              key={user.accountId}
              role="option"
              aria-selected={index === highlighted}
              className={index === highlighted ? 'is-highlighted' : ''}
              onMouseEnter={() => setHighlighted(index)}
              onMouseDown={(event) => {
                // mousedown, not click: the input's blur would close the list first.
                event.preventDefault();
                choose(user);
              }}
            >
              <Avatar name={user.displayName} />
              <span className="picker__name">{user.displayName}</span>
              {user.email ? <span className="picker__meta">{user.email}</span> : null}
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

/** Initials rather than a fetched avatar: a picture per row is a request per row. */
function Avatar({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
  return (
    <span className="avatar" aria-hidden="true">
      {initials}
    </span>
  );
}
