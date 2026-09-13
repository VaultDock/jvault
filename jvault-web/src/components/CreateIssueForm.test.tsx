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
  fieldDisplayNames: {},
  failure: null,
  parts: [
    {
      contentRef: 'ct-1',
      partType: 'CUSTOM_FIELD',
      fieldKey: 'notes',
      classification: 'RESTRICTED',
      mediaType: 'text/plain',
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
    // submitting has already decided how much to type. Two chips: the Notes field and the
    // attachment control, which is externally placed by default.
    expect(screen.getAllByText('Stored in jvault')).toHaveLength(2);
    expect(screen.getByText(/1 field on this form is|One field on this form is/)).toBeDefined();
  });

  it('says attachments go to Jira when that is what policy says, instead of offering an upload', () => {
    render(
      <CreateIssueForm
        definition={{
          ...definition,
          fields: [...definition.fields, field({ key: 'attachment', name: 'Attachment' })],
        }}
        deploymentId="d1"
      />,
    );

    // The API answers 501 for this case rather than quietly keeping a copy policy never asked
    // for. Saying so before someone picks a file beats saying so after.
    expect(screen.getByText(/attachments go to Jira/)).toBeDefined();
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Choose files' }).disabled).toBe(
      true,
    );
  });

  it('uploads chosen files once the ticket exists, not before', async () => {
    const create = vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    const upload = vi.spyOn(api, 'uploadAttachment').mockResolvedValue({
      contentRef: 'ct-9',
      fileName: 'q3.pdf',
      sizeBytes: 4,
      mediaType: 'application/pdf',
      classification: 'RESTRICTED',
      jiraSurrogate: 'Held in jvault',
      link: '/c/ct-9',
    });

    render(<CreateIssueForm definition={definition} deploymentId="d1" />);
    await userEvent.type(screen.getByLabelText(/Summary/), 'With a document');
    await userEvent.upload(
      screen.getByTestId('attachment-input'),
      new File(['abcd'], 'q3.pdf', { type: 'application/pdf' }),
    );

    // Nothing is sent while the form is being filled in: a file uploaded before Create is a file
    // jvault has to explain the existence of if Create is never pressed.
    expect(upload).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(upload).toHaveBeenCalledOnce());
    expect(create).toHaveBeenCalledOnce();
    expect(upload.mock.calls[0]![0]).toBe('tkt-1');
    expect(await screen.findByText('uploaded')).toBeDefined();
  });

  it('a ticket that was created survives a file that was not', async () => {
    vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    vi.spyOn(api, 'uploadAttachment').mockRejectedValue(new Error('disk full'));

    render(<CreateIssueForm definition={definition} deploymentId="d1" />);
    await userEvent.type(screen.getByLabelText(/Summary/), 'With a document');
    await userEvent.upload(
      screen.getByTestId('attachment-input'),
      new File(['abcd'], 'q3.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    // Reporting both outcomes is more use than pretending the whole thing failed — the ticket
    // is real and retrying the upload is the only thing left to do.
    expect(await screen.findByText('tkt-1')).toBeDefined();
    expect(screen.getByText(/some files did not attach/)).toBeDefined();
    expect(screen.getByRole('button', { name: /Retry/ })).toBeDefined();
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
    vi.spyOn(api, 'ticket').mockResolvedValue(created);
    render(<CreateIssueForm definition={definition} deploymentId="d1" />);

    await userEvent.type(screen.getByLabelText(/Summary/), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    // The Jira write is dispatched from the outbox, so "accepted, not yet in Jira" is an
    // ordinary state rather than a failure to hide.
    await screen.findByText('tkt-1');
    expect(screen.getByText('being created')).toBeDefined();
  });

  it('shows the Jira issue key as soon as the outbox has created it', async () => {
    vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    // The create response never carries a key: the issue does not exist when it returns.
    const poll = vi
      .spyOn(api, 'ticket')
      .mockResolvedValue({ ...created, state: 'ACTIVE', issueKey: 'KAN-42' });

    render(<CreateIssueForm definition={definition} deploymentId="d1" />);
    await userEvent.type(screen.getByLabelText(/Summary/), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    // The number is what somebody came here to be told, so the panel waits for it rather than
    // leaving them to go and look.
    expect(await screen.findByText('KAN-42', {}, { timeout: 5000 })).toBeDefined();
    expect(poll).toHaveBeenCalledWith('tkt-1');
  });

  it('says so when Jira refuses the ticket, instead of waiting forever', async () => {
    vi.spyOn(api, 'createTicket').mockResolvedValue(created);
    vi.spyOn(api, 'ticket').mockResolvedValue({ ...created, state: 'FAILED', issueKey: null });

    render(<CreateIssueForm definition={definition} deploymentId="d1" />);
    await userEvent.type(screen.getByLabelText(/Summary/), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByText(/Jira refused/, {}, { timeout: 5000 })).toBeDefined();
  });
});
