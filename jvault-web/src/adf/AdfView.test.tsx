import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AdfView } from './AdfView';
import type { AdfNode } from './types';

const doc = (...content: AdfNode[]): AdfNode => ({ type: 'doc', content });
const para = (...content: AdfNode[]): AdfNode => ({ type: 'paragraph', content });
const text = (value: string, ...marks: string[]): AdfNode => ({
  type: 'text',
  text: value,
  marks: marks.map((type) => ({ type })),
});

afterEach(cleanup);

describe('AdfView', () => {
  it('renders the structure Jira would show', () => {
    const { container } = render(
      <AdfView
        document={doc(
          { type: 'heading', attrs: { level: 2 }, content: [text('Findings')] },
          para(text('The export failed.')),
          {
            type: 'bulletList',
            content: [{ type: 'listItem', content: [para(text('412 rows affected'))] }],
          },
        )}
      />,
    );

    expect(container.querySelector('h2')?.textContent).toBe('Findings');
    expect(container.querySelectorAll('li')).toHaveLength(1);
    expect(screen.getByText('412 rows affected')).toBeDefined();
  });

  it('applies marks as elements rather than as styling attributes', () => {
    const { container } = render(<AdfView document={doc(para(text('urgent', 'strong', 'em')))} />);

    expect(container.querySelector('strong em, em strong')).not.toBeNull();
  });

  it('does not turn stored text into markup', () => {
    const hostile = '<img src=x onerror="alert(1)">';

    const { container } = render(<AdfView document={doc(para(text(hostile)))} />);

    // React escapes this for us; the test is here so that a future refactor towards
    // dangerouslySetInnerHTML fails loudly rather than quietly.
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText(hostile)).toBeDefined();
  });

  it('refuses a link whose scheme is not a location', () => {
    const link: AdfNode = {
      type: 'text',
      text: 'click me',
      marks: [{ type: 'link', attrs: { href: 'javascript:alert(1)' } }],
    };

    const { container } = render(<AdfView document={doc(para(link))} />);

    // The text survives; only its being a link does not.
    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByText('click me')).toBeDefined();
  });

  it('keeps an ordinary link and opens it safely', () => {
    const link: AdfNode = {
      type: 'text',
      text: 'runbook',
      marks: [{ type: 'link', attrs: { href: 'https://example.com/x' } }],
    };

    const { container } = render(<AdfView document={doc(para(link))} />);
    const anchor = container.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('https://example.com/x');
    expect(anchor?.getAttribute('rel')).toContain('noopener');
  });

  it('shows the words of a node type it does not know', () => {
    const future: AdfNode = {
      type: 'panel',
      attrs: { panelType: 'warning' },
      content: [para(text('This matters'))],
    };

    // Dropping it would silently lose somebody's words on a page whose purpose is showing them.
    render(<AdfView document={doc(future)} />);

    expect(screen.getByText('This matters')).toBeDefined();
  });

  it('renders a code block as text, without its marks', () => {
    const block: AdfNode = {
      type: 'codeBlock',
      content: [text('SELECT * FROM payroll', 'strong')],
    };

    const { container } = render(<AdfView document={doc(block)} />);

    expect(container.querySelector('pre code')?.textContent).toBe('SELECT * FROM payroll');
    expect(container.querySelector('pre strong')).toBeNull();
  });
});
