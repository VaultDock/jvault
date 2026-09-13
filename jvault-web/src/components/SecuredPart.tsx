import { useEffect, useState } from 'react';
import { AdfView } from '../adf/AdfView';
import type { AdfNode } from '../adf/types';
import { ApiError, api } from '../api/client';
import type { RenderedContent, TicketPart } from '../api/types';
import { useT } from '../i18n';
import { DownloadIcon, LockIcon, VaultIcon } from './icons';

/**
 * One secured field, shown in place.
 *
 * <p>The point of the page: the value is read here, formatted the way Jira would have shown it,
 * rather than arriving in a downloads folder. Reading it needs VIEW; taking a copy is a separate
 * grant, and a separate act — the button below, which asks for DOWNLOAD and says so plainly when
 * the answer is no.
 */
export function SecuredPart({ part }: { part: TicketPart }) {
  const { t } = useT();
  const [content, setContent] = useState<RenderedContent | null>(null);
  const [error, setError] = useState<string | null>(null);

  const image = isImage(part.mediaType);

  useEffect(() => {
    if (image) {
      // An image is fetched by the browser from the same endpoint, as an image.
      return;
    }
    let live = true;
    api
      .renderContent(part.contentRef)
      .then((rendered) => live && setContent(rendered))
      .catch((cause) => {
        if (live) {
          setError(cause instanceof ApiError ? cause.message : t.errorUnreachable);
        }
      });
    return () => {
      live = false;
    };
  }, [part.contentRef, image, t.errorUnreachable]);

  return (
    <section className="secured">
      <div className="secured__head">
        <VaultIcon />
        <span className="secured__name">{part.fieldKey ?? part.partType.toLowerCase()}</span>
        <span className="chip chip--class">{part.classification.toLowerCase()}</span>
        <DownloadButton contentRef={part.contentRef} />
      </div>

      <div className="secured__body">
        {error ? <p className="field__error">{error}</p> : null}

        {image ? (
          <img
            className="secured__image"
            src={`/api/v1/content/${encodeURIComponent(part.contentRef)}/render`}
            alt={part.fieldKey ?? part.partType}
          />
        ) : content ? (
          <Rendered content={content} />
        ) : error ? null : (
          <div className="skeleton" style={{ height: '2.5rem' }} />
        )}
      </div>
    </section>
  );
}

/**
 * Taking a copy, as a deliberate act.
 *
 * <p>Fetched rather than linked: a refusal belongs beside the thing refused, not on a page of
 * its own where the ticket used to be. What comes back is handed to the browser under the name
 * the server chose, which is the only place the filename is ever disclosed.
 */
function DownloadButton({ contentRef }: { contentRef: string }) {
  const { t } = useT();
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);

  async function save() {
    setBusy(true);
    setRefused(null);
    try {
      const { blob, filename } = await api.downloadContent(contentRef);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      // The object URL pins the blob in memory until it is let go of, but revoking it in this
      // same tick cancels the save in some browsers before it has read a byte.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (cause) {
      setRefused(
        cause instanceof ApiError && cause.status === 403
          ? t.downloadRefused
          : cause instanceof ApiError
            ? cause.message
            : t.errorUnreachable,
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <span className="secured__take">
      {refused ? (
        <span className="secured__refused" role="alert">
          {refused}
        </span>
      ) : null}
      <button type="button" className="linklike" onClick={save} disabled={busy}>
        <DownloadIcon />
        <span>{busy ? t.downloading : t.download}</span>
      </button>
    </span>
  );
}

function Rendered({ content }: { content: RenderedContent }) {
  const { t } = useT();

  switch (content.kind) {
    case 'RICH_TEXT':
      return <AdfView document={content.document as AdfNode} />;

    case 'TEXT':
      return <p className="secured__text">{content.text}</p>;

    case 'TOO_LARGE':
      return (
        <p className="notice">
          <LockIcon />
          <span>{t.tooLargeToShow}</span>
        </p>
      );

    default:
      // Saying so beats an empty box: the field exists, it holds something, and jvault has no
      // way to put it on a page.
      return (
        <p className="notice">
          <LockIcon />
          <span>{t.cannotPreview(content.mediaType ?? 'unknown')}</span>
        </p>
      );
  }
}

function isImage(mediaType: string | null | undefined): boolean {
  // The same short list the server will serve inline. SVG is not on it: it can carry script.
  return (
    mediaType === 'image/png'
    || mediaType === 'image/jpeg'
    || mediaType === 'image/gif'
    || mediaType === 'image/webp'
    || mediaType === 'image/bmp'
  );
}
