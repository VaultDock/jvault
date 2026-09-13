import { describe, expect, it } from 'vitest';
import { imagesFromPaste } from './capture';

/** A DataTransfer stand-in: jsdom has no real clipboard. */
function clipboard(files: { name: string; type: string }[]): DataTransfer {
  return {
    items: files.map((file) => ({
      kind: 'file' as const,
      type: file.type,
      getAsFile: () => new File(['bytes'], file.name, { type: file.type }),
    })),
  } as unknown as DataTransfer;
}

describe('imagesFromPaste', () => {
  it('takes an image off the clipboard', () => {
    const images = imagesFromPaste(clipboard([{ name: 'image.png', type: 'image/png' }]));

    expect(images).toHaveLength(1);
    expect(images[0]!.type).toBe('image/png');
  });

  it('gives a pasted screenshot a name of its own', () => {
    const images = imagesFromPaste(clipboard([{ name: 'image.png', type: 'image/png' }]));

    // Chrome calls every pasted image image.png, so three pastes would look like one file to
    // anything that deduplicates by name — which this form does.
    expect(images[0]!.name).toMatch(/^screenshot-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.png$/);
  });

  it('keeps a real filename when the paste has one', () => {
    const images = imagesFromPaste(clipboard([{ name: 'diagram.png', type: 'image/png' }]));

    expect(images[0]!.name).toBe('diagram.png');
  });

  it('uses the right extension for the format pasted', () => {
    const images = imagesFromPaste(clipboard([{ name: 'image.png', type: 'image/jpeg' }]));

    expect(images[0]!.name).toMatch(/\.jpg$/);
  });

  it('ignores text on the clipboard', () => {
    const text = {
      items: [{ kind: 'string', type: 'text/plain', getAsFile: () => null }],
    } as unknown as DataTransfer;

    // Pasting a paragraph into the attachment area should not produce a file called
    // screenshot-….txt.
    expect(imagesFromPaste(text)).toHaveLength(0);
  });

  it('survives a paste with no clipboard at all', () => {
    expect(imagesFromPaste(null)).toHaveLength(0);
  });
});
