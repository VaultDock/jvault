import { EditorContent, useEditor } from '@tiptap/react';
import Link from '@tiptap/extension-link';
import StarterKit from '@tiptap/starter-kit';
import { useEffect } from 'react';
import { toAdf } from '../adf/toAdf';
import {
  BoldIcon,
  BulletIcon,
  CodeIcon,
  ItalicIcon,
  NumberedIcon,
  QuoteIcon,
} from './icons';

/**
 * The rich-text control.
 *
 * TipTap rather than Atlassian's own editor, which was measured at 4.42 MB gzipped against
 * 0.16 MB for this. The document it produces is converted to ADF on the way out; the server
 * performs the same filtering again, because what arrives there is whatever the caller sent.
 *
 * The extension set is closed on purpose. Every node type enabled here is one the serializer
 * must know how to convert, and one that Jira must accept — an editor that can produce a node
 * neither of those handles is an editor that loses the user's work at submit time.
 */
export function RichTextField({
  value,
  onChange,
  editable,
  id,
}: {
  value: string;
  onChange: (adfJson: string) => void;
  editable: boolean;
  /** Put on the editable element itself, so the field's <label for> actually focuses it. */
  id?: string | undefined;
}) {
  const editor = useEditor({
    editable,
    extensions: [
      StarterKit,
      Link.configure({ openOnClick: false, autolink: true, protocols: ['http', 'https', 'mailto'] }),
    ],
    editorProps: {
      // A label whose `for` names no element is a label that does nothing when clicked, which is
      // how this started: the editor is a contenteditable div, not an input.
      attributes: id ? { id, role: 'textbox', 'aria-multiline': 'true' } : {},
    },
    onUpdate: ({ editor: current }) => onChange(JSON.stringify(toAdf(current.getJSON()))),
  });

  useEffect(() => {
    editor?.setEditable(editable);
  }, [editor, editable]);

  // The value is owned by the form, but the editor holds its own document. Pushing every
  // keystroke back in would fight the cursor, so this only syncs when the form resets the field.
  useEffect(() => {
    if (editor && value === '' && !editor.isEmpty) {
      editor.commands.clearContent();
    }
  }, [editor, value]);

  return (
    <div className="richtext">
      {editor ? <Toolbar editor={editor} disabled={!editable} /> : null}
      <EditorContent editor={editor} className="richtext__body" />
    </div>
  );
}

type EditorInstance = NonNullable<ReturnType<typeof useEditor>>;

function Toolbar({ editor, disabled }: { editor: EditorInstance; disabled: boolean }) {
  const actions = [
    { title: 'Bold', icon: <BoldIcon />, mark: 'bold', run: () => editor.chain().focus().toggleBold().run() },
    { title: 'Italic', icon: <ItalicIcon />, mark: 'italic', run: () => editor.chain().focus().toggleItalic().run() },
    { title: 'Bullet list', icon: <BulletIcon />, mark: 'bulletList', run: () => editor.chain().focus().toggleBulletList().run() },
    { title: 'Numbered list', icon: <NumberedIcon />, mark: 'orderedList', run: () => editor.chain().focus().toggleOrderedList().run() },
    { title: 'Quote', icon: <QuoteIcon />, mark: 'blockquote', run: () => editor.chain().focus().toggleBlockquote().run() },
    { title: 'Code block', icon: <CodeIcon />, mark: 'codeBlock', run: () => editor.chain().focus().toggleCodeBlock().run() },
  ];

  return (
    <div className="richtext__toolbar">
      {actions.map((action) => (
        <button
          key={action.title}
          type="button"
          title={action.title}
          aria-label={action.title}
          disabled={disabled}
          aria-pressed={editor.isActive(action.mark)}
          onMouseDown={(event) => event.preventDefault()}
          onClick={action.run}
        >
          {action.icon}
        </button>
      ))}
    </div>
  );
}
