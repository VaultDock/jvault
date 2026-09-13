/** Atlassian Document Format, as much of it as jvault emits. */
export interface AdfNode {
  type: string;
  attrs?: Record<string, unknown>;
  content?: AdfNode[];
  marks?: AdfMark[];
  text?: string;
}

export interface AdfMark {
  type: string;
  attrs?: Record<string, unknown>;
}

export interface AdfDocument extends AdfNode {
  type: 'doc';
  version: 1;
  content: AdfNode[];
}

/** What TipTap hands back from `editor.getJSON()`. */
export interface EditorNode {
  type?: string;
  attrs?: Record<string, unknown>;
  content?: EditorNode[];
  marks?: { type: string; attrs?: Record<string, unknown> }[];
  text?: string;
}
