import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateIssueForm } from './CreateIssueForm';
import type { FormDefinition, FormField } from '../api/types';
import { api } from '../api/client';
import { ApiError } from '../api/client';

const field = (over: Partial<FormField> & Pick<FormField, 'key'>): FormField => ({
  name: over.key,
  required: false,
  schemaType: 'string',
  customType: null,
  allowedValues: [],
  hasMoreOptions: false,
  placement: 'JIRA',
  classification: null,
  allowOverride: false,
  supportLevel: 'REPRODUCED',
  control: 'TEXT',
  ...over,
});

const definition: FormDefinition = {
  projectKey: 'KAN',
  issueTypeId: '10004',
  fields: [
    field({ key: 'summary', name: 'Summary', required: true }),
    field({
      key: 'notes',
      name: 'Notes',
      placement: 'EXTERNAL',
      classification: 'RESTRICTED',
    }),
    field({ key: 'customfield_10019', name: 'Rank', supportLevel: 'READ_ONLY' }),
  ],
};

const created = {
  ticketRef: 'tkt-1',
  state: 'ACCEPTED',
  issueKey: null,
  jiraFields: {},
  parts: [
    {
      contentRef: 'ct-1',
      partType: 'CUSTOM_FIELD',
      fieldKey: 'notes',
      classification: 'RESTRICTED',
      link: '/c/ct-1',
    },
  ],
};

beforeEach(() => {
  vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111');
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('CreateIssueForm', () => {
  it('marks a field that leaves Jira, before anything is typed into it', () => {
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    // The whole reason this form exists rather than Jira's own. A user who learns after
    // submitting has already decided how much to type.
    expect(screen.getByText('Stored in jvault')).toBeDefined();
    expect(screen.getByText(/1 field on this form is|One field on this form is/)).toBeDefined();
  });

  it('disables a field Jira maintains rather than dropping what is typed into it', () => {
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    expect(screen.getByLabelText<HTMLInputElement>(/Rank/).disabled).toBe(true);
  });

  it('sends only the fields that were filled in, and never a read-only one', async () => {
    const create = vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    await userEvent.type(screen.getByLabelText(/Summary/), 'Disk filling up');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(create).toHaveBeenCalled());
    const [body] = create.mock.calls[0]!;
    // An empty field is not an instruction to clear one, and a read-only field is not the
    // user's to send at all.
    expect(body.fields).toEqual({ summary: 'Disk filling up' });
    expect(body.projectKey).toBe('KAN');
  });

  it('reuses the idempotency key when the connection drops, so a retry cannot duplicate', async () => {
    const create = vi
      .spyOn(api, 'createTicket')
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(created);
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    await userEvent.type(screen.getByLabelText(/Summary/), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText(/Retrying will not create a duplicate/);
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(2));
    // The request may well have reached Jira. Sending a fresh key would be asking for a second
    // issue; sending the same one gets the first ticket back.
    expect(create.mock.calls[0]![1]).toBe(create.mock.calls[1]![1]);
  });

  it('starts a new attempt after a rejection, which created nothing', async () => {
    const problem = new ApiError(
      { title: 'Invalid', status: 400, errors: [{ field: 'fields.summary', code: 'REQUIRED' }] },
      400,
    );
    const create = vi
      .spyOn(api, 'createTicket')
      .mockRejectedValueOnce(problem)
      .mockResolvedValueOnce(created);
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('11111111-1111-4111-8111-111111111111')
      .mockReturnValue('22222222-2222-4222-8222-222222222222');

    render(<CreateIssueForm definition={definition} deploymentId="d1" />);
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    // The field-level error lands on the control that caused it, not in a banner at the top.
    await screen.findByText('REQUIRED');

    await userEvent.type(screen.getByLabelText(/Summary/), 'now filled');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(2));
    expect(create.mock.calls[0]![1]).not.toBe(create.mock.calls[1]![1]);
  });

  it('shows the ticket before Jira has an issue key for it', async () => {
    vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    await userEvent.type(screen.getByLabelText(/Summary/), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    // The Jira write is dispatched from the outbox, so "accepted, not yet in Jira" is an
    // ordinary state rather than a failure to hide.
    await screen.findByText('tkt-1');
    expect(screen.getByText('being created')).toBeDefined();
    expect(screen.getByRole('link', { name: 'open' }).getAttribute('href')).toBe('/c/ct-1');
  });
});
