import { api } from '../api/client';
import { useT } from '../i18n';
import { SearchPicker, initialsOf, type Choice } from './SearchPicker';

/**
 * A person, chosen by name.
 *
 * <p>Jira accepts an account id and nothing else, and an account id is the last thing anyone
 * knows about a colleague. The control this replaced was a text box labelled "Account id", which
 * is less a control than an obstacle.
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

  return (
    <SearchPicker
      id={id}
      value={value}
      disabled={disabled}
      required={required}
      placeholder={t.searchPeople}
      describe={(choice) => initialsOf(choice.label)}
      onChange={onChange}
      search={async (query) => {
        const people = await api.searchUsers(projectKey, query, assignable);
        return people.map<Choice>((person) => ({
          value: person.accountId,
          label: person.displayName,
          meta: person.email,
        }));
      }}
    />
  );
}
