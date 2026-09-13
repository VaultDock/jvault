import type { AdfDocument, AdfMark, AdfNode, EditorNode } from './types';

/**
 * Turns the editor's document into Atlassian Document Format.
 *
 * <p>ProseMirror and ADF are close enough to tempt you into passing the JSON straight through,
 * and different enough that doing so produces a document Jira rejects with a 400 that names
 * nothing. The differences are small and specific — `horizontalRule` is `rule`, `bold` is
 * `strong`, an ordered list's `start` is an `order` — so they are handled here, once, rather
 * than discovered one rejection at a time.
 *
 * The conversion is a closed allow-list. A node type this does not know is dropped rather than
 * forwarded, because an unknown node is either something a future editor extension introduced
 * (in which case Jira would reject the whole document) or something an attacker put in the
 * payload (in which case forwarding it is worse). Text inside a dropped node is kept: losing
 * someone's formatting is a nuisance, losing their words is a bug.
 */

const BLOCKS = new Set([
  'paragraph',
  'heading',
  'bulletList',
  'orderedList',
  'listItem',
  'blockquote',
  'codeBlock',
  'rule',
  'hardBreak',
]);

/** ProseMirror's name on the left, ADF's on the right, where they differ. */
const NODE_NAMES: Record<string, string> = {
  horizontalRule: 'rule',
};

const MARK_NAMES: Record<string, string> = {
  bold: 'strong',
  italic: 'em',
};

const MARKS = new Set(['strong', 'em', 'strike', 'code', 'underline', 'subsup', 'link']);

export function toAdf(doc: EditorNode | null | undefined): AdfDocument {
  const content = doc?.content ? convertAll(doc.content) : [];

  // An empty description still has to be a valid document. Jira rejects a malformed one, and a
  // rejected create strands the ticket in the outbox rather than failing in front of the user.
  return { type: 'doc', version: 1, content: content.length > 0 ? content : [emptyParagraph()] };
}

function convertAll(nodes: EditorNode[]): AdfNode[] {
  return nodes.flatMap(convert);
}

/** Returns zero, one, or — when an unknown node is unwrapped — several nodes. */
function convert(node: EditorNode): AdfNode[] {
  if (node.type === 'text') {
    return textNode(node);
  }

  const type = NODE_NAMES[node.type ?? ''] ?? node.type ?? '';
  if (!BLOCKS.has(type)) {
    // Keep the words, drop the wrapper.
    return node.content ? convertAll(node.content) : [];
  }

  switch (type) {
    case 'rule':
    case 'hardBreak':
      return [{ type }];

    case 'codeBlock':
      return [codeBlock(node)];

    case 'heading':
      return [withContent({ type, attrs: { level: headingLevel(node) } }, node)];

    case 'orderedList': {
      const start = Number(node.attrs?.['start'] ?? 1);
      const list: AdfNode = { type };
      if (Number.isInteger(start) && start > 1) {
        list.attrs = { order: start };
      }
      return [listWithItems(list, node)];
    }

    case 'bulletList':
      return [listWithItems({ type }, node)];

    case 'listItem':
    case 'blockquote':
      // Both must contain blocks. A list item holding bare text is valid ProseMirror in some
      // schemas and invalid ADF everywhere.
      return [blockContainer({ type }, node)];

    default:
      return [withContent({ type }, node)];
  }
}

function textNode(node: EditorNode): AdfNode[] {
  // ADF has no empty text node; an empty one invalidates the whole document.
  if (!node.text) {
    return [];
  }
  const marks = convertMarks(node.marks);
  const text: AdfNode = { type: 'text', text: node.text };
  if (marks.length > 0) {
    text.marks = marks;
  }
  return [text];
}

function convertMarks(marks: EditorNode['marks']): AdfMark[] {
  if (!marks) {
    return [];
  }
  const converted: AdfMark[] = [];
  for (const mark of marks) {
    const type = MARK_NAMES[mark.type] ?? mark.type;
    if (!MARKS.has(type)) {
      continue;
    }
    if (type === 'link') {
      const href = mark.attrs?.['href'];
      // A link with no target is not a link. Dropping the mark keeps the text.
      if (typeof href === 'string' && isSafeHref(href)) {
        converted.push({ type, attrs: { href } });
      }
      continue;
    }
    converted.push({ type });
  }
  return converted;
}

/**
 * Only schemes that mean "somewhere else on the web".
 *
 * A `javascript:` href in a Jira description is a stored payload aimed at whoever opens the
 * issue; it has no legitimate use here, and the editor will happily carry one if pasted.
 */
function isSafeHref(href: string): boolean {
  const trimmed = href.trim().toLowerCase();
  return (
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('mailto:') ||
    trimmed.startsWith('/')
  );
}

function codeBlock(node: EditorNode): AdfNode {
  // Code block text carries no marks in ADF; bolding inside code is not a thing.
  const text = collectText(node);
  const block: AdfNode = { type: 'codeBlock' };
  const language = node.attrs?.['language'];
  if (typeof language === 'string' && language !== '') {
    block.attrs = { language };
  }
  if (text !== '') {
    block.content = [{ type: 'text', text }];
  }
  return block;
}

function collectText(node: EditorNode): string {
  if (typeof node.text === 'string') {
    return node.text;
  }
  return (node.content ?? []).map(collectText).join('');
}

function headingLevel(node: EditorNode): number {
  const level = Number(node.attrs?.['level'] ?? 1);
  return Number.isInteger(level) && level >= 1 && level <= 6 ? level : 1;
}

function withContent(shell: AdfNode, node: EditorNode): AdfNode {
  const content = node.content ? convertAll(node.content) : [];
  return content.length > 0 ? { ...shell, content } : shell;
}

/** A list whose items are all list items, and which has at least one. */
function listWithItems(shell: AdfNode, node: EditorNode): AdfNode {
  const items = (node.content ?? [])
    .flatMap(convert)
    .filter((child) => child.type === 'listItem');
  return { ...shell, content: items.length > 0 ? items : [blockContainer({ type: 'listItem' }, {})] };
}

/** A container ADF requires to hold at least one block. */
function blockContainer(shell: AdfNode, node: EditorNode): AdfNode {
  const content = (node.content ? convertAll(node.content) : []).filter(isBlock);
  return { ...shell, content: content.length > 0 ? content : [emptyParagraph()] };
}

function isBlock(node: AdfNode): boolean {
  return node.type !== 'text' && node.type !== 'hardBreak';
}

function emptyParagraph(): AdfNode {
  return { type: 'paragraph' };
}

/** Whether a stored string is already an ADF document, as opposed to plain text. */
export function isAdf(value: string): boolean {
  if (!value.trimStart().startsWith('{')) {
    return false;
  }
  try {
    const parsed: unknown = JSON.parse(value);
    return (
      typeof parsed === 'object' &&
      parsed !== null &&
      (parsed as { type?: unknown }).type === 'doc'
    );
  } catch {
    return false;
  }
}

/** Flattens a document to plain text, for a preview or a field that is not rich text. */
export function toPlainText(doc: EditorNode | null | undefined): string {
  if (!doc?.content) {
    return '';
  }
  return doc.content.map(blockText).join('\n\n').trim();
}

function blockText(node: EditorNode): string {
  if (node.type === 'hardBreak') {
    return '\n';
  }
  if (typeof node.text === 'string') {
    return node.text;
  }
  const separator = node.type === 'bulletList' || node.type === 'orderedList' ? '\n' : '';
  return (node.content ?? []).map(blockText).join(separator);
}
