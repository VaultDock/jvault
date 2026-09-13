import { describe, expect, it } from 'vitest';
import { isAdf, toAdf, toPlainText } from './toAdf';
import type { EditorNode } from './types';

const doc = (...content: EditorNode[]): EditorNode => ({ type: 'doc', content });
const para = (...content: EditorNode[]): EditorNode => ({ type: 'paragraph', content });
const text = (value: string, ...marks: string[]): EditorNode => ({
  type: 'text',
  text: value,
  marks: marks.map((type) => ({ type })),
});

describe('toAdf', () => {
  it('renames the nodes and marks whose names differ from ProseMirror', () => {
    // The whole reason this function exists. Passing the editor's JSON through unchanged
    // produces a 400 from Jira that names nothing.
    const result = toAdf(doc(para(text('hi', 'bold', 'italic')), { type: 'horizontalRule' }));

    expect(result.content[0]?.content?.[0]?.marks).toEqual([{ type: 'strong' }, { type: 'em' }]);
    expect(result.content[1]).toEqual({ type: 'rule' });
  });

  it('produces a valid document for an empty editor', () => {
    // An empty description must still be well-formed: Jira rejects a malformed one, and the
    // rejection strands the ticket in the outbox instead of failing in front of the user.
    expect(toAdf(doc())).toEqual({
      type: 'doc',
      version: 1,
      content: [{ type: 'paragraph' }],
    });
    expect(toAdf(null)).toEqual({ type: 'doc', version: 1, content: [{ type: 'paragraph' }] });
  });

  it('drops empty text nodes, which ADF does not allow', () => {
    const result = toAdf(doc(para({ type: 'text', text: '' }, text('kept'))));

    expect(result.content[0]?.content).toEqual([{ type: 'text', text: 'kept' }]);
  });

  it('keeps the words when it drops a node it does not know', () => {
    // A future editor extension, or something injected into the payload. Either way the
    // document must not carry it — but losing formatting is a nuisance and losing someone's
    // words is a bug, so the text is unwrapped rather than discarded.
    const result = toAdf(doc({ type: 'someFutureNode', content: [para(text('important'))] }));

    expect(result.content).toEqual([
      { type: 'paragraph', content: [{ type: 'text', text: 'important' }] },
    ]);
  });

  it('drops a mark it does not know without touching the text', () => {
    const result = toAdf(doc(para(text('plain', 'textColor'))));

    expect(result.content[0]?.content).toEqual([{ type: 'text', text: 'plain' }]);
  });

  it('refuses a link whose scheme is not a location', () => {
    // A javascript: href in a description is a payload aimed at whoever opens the issue. The
    // editor will carry one if it is pasted in.
    const hostile: EditorNode = {
      type: 'text',
      text: 'click me',
      marks: [{ type: 'link', attrs: { href: ' JavaScript:alert(1)' } }],
    };

    const result = toAdf(doc(para(hostile)));

    expect(result.content[0]?.content).toEqual([{ type: 'text', text: 'click me' }]);
  });

  it('keeps an ordinary link, with only its href', () => {
    const link: EditorNode = {
      type: 'text',
      text: 'runbook',
      marks: [{ type: 'link', attrs: { href: 'https://example.com/x', target: '_blank' } }],
    };

    const result = toAdf(doc(para(link)));

    expect(result.content[0]?.content?.[0]?.marks).toEqual([
      { type: 'link', attrs: { href: 'https://example.com/x' } },
    ]);
  });

  it('strips marks from code block text and keeps the language', () => {
    const block: EditorNode = {
      type: 'codeBlock',
      attrs: { language: 'java' },
      content: [text('int x = 1;', 'bold')],
    };

    expect(toAdf(doc(block)).content[0]).toEqual({
      type: 'codeBlock',
      attrs: { language: 'java' },
      content: [{ type: 'text', text: 'int x = 1;' }],
    });
  });

  it('gives list items and quotes the block content ADF requires of them', () => {
    // Bare text inside a list item is valid in some ProseMirror schemas and invalid ADF
    // everywhere, and an empty list item is invalid too.
    const list: EditorNode = {
      type: 'bulletList',
      content: [{ type: 'listItem', content: [text('loose')] }, { type: 'listItem' }],
    };

    expect(toAdf(doc(list)).content[0]).toEqual({
      type: 'bulletList',
      content: [
        { type: 'listItem', content: [{ type: 'paragraph' }] },
        { type: 'listItem', content: [{ type: 'paragraph' }] },
      ],
    });
  });

  it('carries an ordered list start across as order, and omits the default', () => {
    const from = (start: number): EditorNode => ({
      type: 'orderedList',
      attrs: { start },
      content: [{ type: 'listItem', content: [para(text('one'))] }],
    });

    expect(toAdf(doc(from(3))).content[0]?.attrs).toEqual({ order: 3 });
    expect(toAdf(doc(from(1))).content[0]?.attrs).toBeUndefined();
  });

  it('clamps a heading level to one ADF accepts', () => {
    const heading = (level: unknown): EditorNode => ({
      type: 'heading',
      attrs: { level },
      content: [text('h')],
    });

    expect(toAdf(doc(heading(3))).content[0]?.attrs).toEqual({ level: 3 });
    expect(toAdf(doc(heading(9))).content[0]?.attrs).toEqual({ level: 1 });
    expect(toAdf(doc(heading('big'))).content[0]?.attrs).toEqual({ level: 1 });
  });
});

describe('isAdf', () => {
  it('tells a document from text that merely looks like one', () => {
    expect(isAdf(JSON.stringify(toAdf(doc(para(text('x'))))))).toBe(true);
    expect(isAdf('{"type":"notADoc"}')).toBe(false);
    expect(isAdf('{ not json at all')).toBe(false);
    expect(isAdf('a normal description')).toBe(false);
  });
});

describe('toPlainText', () => {
  it('flattens blocks for a field that is not rich text', () => {
    const result = toPlainText(doc(para(text('one')), para(text('two'))));

    expect(result).toBe('one\n\ntwo');
  });
});
