import { EditorContent, useEditor } from '@tiptap/react';
import Link from '@tiptap/extension-link';
import StarterKit from '@tiptap/starter-kit';
import { useEffect } from 'react';
import { toAdf } from '../adf/toAdf';

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
}: {
  value: string;
  onChange: (adfJson: string) => void;
  editable: boolean;
}) {
  const editor = useEditor({
    editable,
    extensions: [
      StarterKit.configure({
        // Neither has an ADF equivalent jvault emits, so neither is offered.
        horizontalRule: {},
        codeBlock: {},
      }),
      Link.configure({ openOnClick: false, autolink: true, protocols: ['http', 'https', 'mailto'] }),
    ],
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
    { label: 'B', title: 'Bold', run: () => editor.chain().focus().toggleBold().run(), mark: 'bold' },
    {
      label: 'I',
      title: 'Italic',
      run: () => editor.chain().focus().toggleItalic().run(),
      mark: 'italic',
    },
    {
      label: '•',
      title: 'Bullet list',
      run: () => editor.chain().focus().toggleBulletList().run(),
      mark: 'bulletList',
    },
    {
      label: '1.',
      title: 'Numbered list',
      run: () => editor.chain().focus().toggleOrderedList().run(),
      mark: 'orderedList',
    },
    {
      label: '<>',
      title: 'Code block',
      run: () => editor.chain().focus().toggleCodeBlock().run(),
      mark: 'codeBlock',
    },
  ];

  return (
    <div className="richtext__toolbar">
      {actions.map((action) => (
        <button
          key={action.title}
          type="button"
          title={action.title}
          disabled={disabled}
          aria-pressed={editor.isActive(action.mark)}
          onClick={action.run}
        >
          {action.label}
        </button>
      ))}
    </div>
  );
}
