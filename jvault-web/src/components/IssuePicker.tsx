import { api } from '../api/client';
import { useT } from '../i18n';
import { SearchPicker, type Choice } from './SearchPicker';

/**
 * A parent issue, chosen by its summary.
 *
 * <p>A key is at least recognisable, unlike an account id, but nobody remembers which of forty
 * of them is the epic they meant. Candidates are the issues one level above this type in the
 * hierarchy — epics for a task, tasks for a subtask, nothing for an epic. Offering anything
 * wider produces a create that Jira refuses with a field error, which is a poor way to find out.
 */
export function IssuePicker({
  id,
  projectKey,
  issueTypeId,
  value,
  disabled,
  required,
  onChange,
}: {
  id: string;
  projectKey: string;
  /** The type being created. A parent sits exactly one level above it. */
  issueTypeId: string;
  value: string;
  disabled: boolean;
  required: boolean;
  onChange: (issueKey: string) => void;
}) {
  const { t } = useT();

  return (
    <SearchPicker
      id={id}
      value={value}
      disabled={disabled}
      required={required}
      placeholder={t.searchIssues}
      onChange={onChange}
      search={async (query) => {
        const issues = await api.searchIssues(projectKey, query, issueTypeId);
        return issues.map<Choice>((issue) => ({
          value: issue.key,
          // The key leads, because that is what gets sent and what appears in Jira afterwards.
          label: issue.key,
          meta: issue.summary,
          badge: issue.issueTypeName ? issue.issueTypeName.slice(0, 2).toUpperCase() : null,
        }));
      }}
    />
  );
}
