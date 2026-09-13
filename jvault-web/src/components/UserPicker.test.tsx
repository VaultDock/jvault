import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { TranslationProvider } from '../i18n';
import { UserPicker } from './UserPicker';

const PEOPLE = [
  { accountId: '5b10a2', displayName: 'Ada Lovelace', email: 'ada@example.com', active: true },
  { accountId: '5b10a3', displayName: 'Grace Hopper', email: null, active: true },
];

function renderPicker(onChange = vi.fn()) {
  render(
    <TranslationProvider jiraLocale="en_GB">
      <UserPicker
        id="field-assignee"
        projectKey="KAN"
        assignable
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

describe('UserPicker', () => {
  it('sends the account id Jira needs, having shown the name a person knows', async () => {
    const search = vi.spyOn(api, 'searchUsers').mockResolvedValue(PEOPLE);
    const onChange = renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await waitFor(() => expect(search).toHaveBeenCalled());
    await userEvent.click(await screen.findByText('Ada Lovelace'));

    // The whole point: nobody knows their colleague's account id, and Jira accepts nothing else.
    expect(onChange).toHaveBeenCalledWith('5b10a2');
  });

  it('searches the assignable list for this project, not everyone', async () => {
    const search = vi.spyOn(api, 'searchUsers').mockResolvedValue(PEOPLE);
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.type(screen.getByRole('combobox'), 'gra');

    await waitFor(() => expect(search).toHaveBeenCalledWith('KAN', 'gra', true));
  });

  it('does not fire a request per keystroke', async () => {
    const search = vi.spyOn(api, 'searchUsers').mockResolvedValue(PEOPLE);
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.type(screen.getByRole('combobox'), 'grace');

    // Jira rate-limits per account, and five letters is five calls without a debounce.
    await waitFor(() => expect(search).toHaveBeenCalled());
    expect(search.mock.calls.length).toBeLessThan(5);
  });

  it('can be driven from the keyboard', async () => {
    vi.spyOn(api, 'searchUsers').mockResolvedValue(PEOPLE);
    const onChange = renderPicker();

    await userEvent.click(screen.getByRole('combobox'));
    await screen.findByText('Grace Hopper');
    await userEvent.keyboard('{ArrowDown}{Enter}');

    expect(onChange).toHaveBeenCalledWith('5b10a3');
  });

  it('says so when there is nobody, rather than showing an empty box', async () => {
    vi.spyOn(api, 'searchUsers').mockResolvedValue([]);
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));

    expect(await screen.findByText('No matches')).toBeDefined();
  });

  it('a failed search leaves the field usable', async () => {
    vi.spyOn(api, 'searchUsers').mockRejectedValue(new Error('Jira is down'));
    renderPicker();

    await userEvent.click(screen.getByRole('combobox'));

    // An unreachable Jira must not take the whole form down with it.
    expect(await screen.findByText('No matches')).toBeDefined();
    expect(screen.getByRole('combobox')).toBeDefined();
  });
});
