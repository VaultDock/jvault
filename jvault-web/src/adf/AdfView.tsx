import type { ReactNode } from 'react';
import type { AdfMark, AdfNode } from './types';

/**
 * Renders an Atlassian Document Format document the way Jira would show it.
 *
 * <p>The counterpart of the serializer, and closed in the same way: a node type this does not
 * know is rendered as its text rather than skipped. Dropping an unknown node would silently lose
 * somebody's words on a page whose whole purpose is showing them.
 *
 * <p>No HTML is produced from the document's own strings. Every value becomes a React text node,
 * so a description containing markup is a description containing markup, not markup.
 */
export function AdfView({ document }: { document: AdfNode }) {
  return <div className="adf">{children(document)}</div>;
}

function children(node: AdfNode): ReactNode {
  return (node.content ?? []).map((child, index) => (
    <Node key={index} node={child} />
  ));
}

function Node({ node }: { node: AdfNode }): ReactNode {
  switch (node.type) {
    case 'paragraph':
      return <p>{children(node)}</p>;

    case 'heading': {
      const level = Math.min(6, Math.max(1, Number(node.attrs?.['level'] ?? 1)));
      const Tag = `h${level}` as 'h1';
      return <Tag>{children(node)}</Tag>;
    }

    case 'bulletList':
      return <ul>{children(node)}</ul>;

    case 'orderedList': {
      const order = Number(node.attrs?.['order'] ?? 1);
      return <ol start={Number.isInteger(order) && order > 1 ? order : 1}>{children(node)}</ol>;
    }

    case 'listItem':
      return <li>{children(node)}</li>;

    case 'blockquote':
      return <blockquote>{children(node)}</blockquote>;

    case 'codeBlock':
      return (
        <pre>
          <code>{plainText(node)}</code>
        </pre>
      );

    case 'rule':
      return <hr />;

    case 'hardBreak':
      return <br />;

    case 'text':
      return marked(node.text ?? '', node.marks);

    default:
      // Something this version does not render. Its words still belong to whoever wrote them.
      return <>{children(node)}</>;
  }
}

/** Wraps text in the elements its marks call for, innermost first. */
function marked(text: string, marks: AdfMark[] | undefined): ReactNode {
  let rendered: ReactNode = text;

  for (const mark of marks ?? []) {
    switch (mark.type) {
      case 'strong':
        rendered = <strong>{rendered}</strong>;
        break;
      case 'em':
        rendered = <em>{rendered}</em>;
        break;
      case 'strike':
        rendered = <s>{rendered}</s>;
        break;
      case 'underline':
        rendered = <u>{rendered}</u>;
        break;
      case 'code':
        rendered = <code>{rendered}</code>;
        break;
      case 'link': {
        const href = mark.attrs?.['href'];
        // The server sanitises this on the way in and again on the way out to Jira. Checking
        // here too costs one comparison and closes the case where neither ran — a document
        // stored before the sanitiser existed, say.
        rendered = isSafeHref(href) ? (
          <a href={String(href)} rel="noreferrer noopener" target="_blank">
            {rendered}
          </a>
        ) : (
          rendered
        );
        break;
      }
      default:
        // An unknown mark changes how text looks, never whether it is shown.
        break;
    }
  }
  return rendered;
}

function isSafeHref(href: unknown): boolean {
  if (typeof href !== 'string') {
    return false;
  }
  const trimmed = href.trim().toLowerCase();
  return (
    trimmed.startsWith('http://') || trimmed.startsWith('https://') || trimmed.startsWith('mailto:')
  );
}

/** Code blocks hold text and nothing else. */
function plainText(node: AdfNode): string {
  if (typeof node.text === 'string') {
    return node.text;
  }
  return (node.content ?? []).map(plainText).join('');
}
