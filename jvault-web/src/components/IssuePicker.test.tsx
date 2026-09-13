import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { TranslationProvider } from '../i18n';
import { IssuePicker } from './IssuePicker';

const ISSUES = [
  { key: 'KAN-1', summary: 'Migrate the payroll export', issueTypeName: 'Epic' },
  { key: 'KAN-2', summary: 'Rotate the signing keys', issueTypeName: 'Task' },
];

function renderPicker(onChange = vi.fn()) {
  render(
    <TranslationProvider jiraLocale="en_GB">
      <IssuePicker
        id="field-parent"
        projectKey="KAN"
        issueTypeId="10004"
        value=""
        disabled={false}
        required={false}
        onChange={onChange}
      />
    </TranslationProvider>,
  );
  return onChange;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('IssuePicker', () => {
  it('sends the issue key, having shown the summary a person would recognise', async () => {
    const search = vi.spyOn(api, 'searchIssues').mockResolvedValue(ISSUES);
    const onChange = renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await waitFor(() => expect(search).toHaveBeenCalled());
    // A key is recognisable in a way an account id is not, and still nobody remembers which of
    // forty of them is the epic they meant.
    await userEvent.click(await screen.findByText('Migrate the payroll export'));

    expect(onChange).toHaveBeenCalledWith('KAN-1');
  });

  it('searches within the project the form is for', async () => {
    const search = vi.spyOn(api, 'searchIssues').mockResolvedValue(ISSUES);
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.type(screen.getByRole('combobox'), 'payroll');

    // The type travels with the query: a parent sits one level above it, and without that the
    // server would offer candidates Jira refuses.
    await waitFor(() => expect(search).toHaveBeenCalledWith('KAN', 'payroll', '10004'));
  });

  it('does not fire a request per keystroke', async () => {
    const search = vi.spyOn(api, 'searchIssues').mockResolvedValue(ISSUES);
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.type(screen.getByRole('combobox'), 'payroll');

    await waitFor(() => expect(search).toHaveBeenCalled());
    expect(search.mock.calls.length).toBeLessThan(7);
  });

  it('a failed search leaves the field usable', async () => {
    vi.spyOn(api, 'searchIssues').mockRejectedValue(new Error('Jira is down'));
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));

    // An unreachable Jira must not take the form down with it.
    expect(await screen.findByText('No matches')).toBeDefined();
    expect(screen.getByRole('combobox')).toBeDefined();
  });

  it('a chosen issue can be cleared', async () => {
    vi.spyOn(api, 'searchIssues').mockResolvedValue(ISSUES);
    const onChange = renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByText('Rotate the signing keys'));
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(onChange).toHaveBeenLastCalledWith('');
  });
});
