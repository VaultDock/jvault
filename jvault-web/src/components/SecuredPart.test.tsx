import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from '../api/client';
import type { TicketPart } from '../api/types';
import { TranslationProvider } from '../i18n';
import { SecuredPart } from './SecuredPart';

const PART: TicketPart = {
  contentRef: 'c0ffee',
  partType: 'ATTACHMENT',
  fieldKey: null,
  classification: 'CONFIDENTIAL',
  mediaType: 'text/plain',
  link: 'https://jvault.example.com/c/c0ffee',
};

function renderPart() {
  vi.spyOn(api, 'renderContent').mockResolvedValue({
    kind: 'TEXT',
    text: 'incident notes',
    mediaType: 'text/plain',
    sizeBytes: 14,
    document: null,
  });
  render(
    <TranslationProvider jiraLocale="en_GB">
      <SecuredPart part={PART} />
    </TranslationProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('SecuredPart', () => {
  it('saves the file under the name the server gave it', async () => {
    const download = vi.spyOn(api, 'downloadContent').mockResolvedValue({
      blob: new Blob(['incident notes']),
      filename: 'evidence.pcap',
    });
    // jsdom has neither object URLs nor navigation, so the anchor is watched instead.
    const createObjectURL = vi.fn(() => 'blob:jvault/1');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL });
    const clicks: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicks.push(this);
    });

    renderPart();
    await userEvent.click(await screen.findByRole('button', { name: /download/i }));

    await waitFor(() => expect(download).toHaveBeenCalledWith('c0ffee'));
    // The filename is disclosed only in the download response, so it is the only place the
    // browser can learn what to call the file.
    expect(clicks[0]?.download).toBe('evidence.pcap');
    expect(clicks[0]?.href).toBe('blob:jvault/1');
  });

  it('says a refusal in place rather than leaving the reader on a page of JSON', async () => {
    vi.spyOn(api, 'downloadContent').mockRejectedValue(
      new ApiError({ title: 'Forbidden', status: 403, code: 'NO_VAULT_GRANT' }, 403),
    );

    renderPart();
    await userEvent.click(await screen.findByRole('button', { name: /download/i }));

    // Reading and taking a copy are separate grants; being told so beside the field is the
    // difference between "you may not" and "something went wrong".
    expect((await screen.findByRole('alert')).textContent).toMatch(/not take a copy/i);
    // And the field is still readable, which is what the caller does have.
    expect(screen.getByText('incident notes')).toBeTruthy();
  });
});
