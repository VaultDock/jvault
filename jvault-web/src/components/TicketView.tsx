import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { TicketResponse } from '../api/types';
import { useT } from '../i18n';
import { AlertIcon, CheckIcon, LockIcon } from './icons';
import { SecuredPart } from './SecuredPart';

/**
 * One ticket, with what Jira is not allowed to hold.
 *
 * <p>This is what the link on the Jira issue points at. Someone reading the issue sees a
 * description saying the content is secured; this is where they go to read it, and whether they
 * can is decided here rather than by whether they found the link.
 *
 * <p>The content is not in this response. Each part is a link, followed separately, authorized
 * separately and audited separately — so opening the page is not the same act as reading a
 * field, and the audit trail can tell them apart.
 */
export function TicketView({ ticketRef }: { ticketRef: string }) {
  const { t } = useT();
  const [ticket, setTicket] = useState<TicketResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<number | null>(null);

  useEffect(() => {
    let live = true;

    function load() {
      api
        .ticket(ticketRef)
        .then((found) => {
          if (live) {
            setTicket(found);
            setError(null);
          }
        })
        .catch((cause) => {
          if (!live) {
            return;
          }
          setStatus(cause instanceof ApiError ? cause.status : null);
          setError(cause instanceof ApiError ? cause.message : t.errorUnreachable);
        });
    }

    load();
    // The Jira issue is created from the outbox a moment after the ticket, so a page opened
    // immediately would otherwise sit on "being created" until somebody reloaded it.
    const timer = setInterval(load, 3000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [ticketRef, t.errorUnreachable]);

  if (error) {
    return (
      <main>
        <p className="notice notice--error" role="alert">
          <AlertIcon />
          {/* 404 covers both "no such ticket" and "not yours": telling them apart would be an
              existence oracle for anyone who can guess a reference. */}
          <span>{status === 404 ? t.ticketNotVisible : error}</span>
        </p>
      </main>
    );
  }

  if (!ticket) {
    return (
      <main>
        <div className="card">
          <div className="form-body" aria-busy="true">
            <div className="skeleton" style={{ width: '40%', marginBottom: '0.6rem' }} />
            <div className="skeleton" style={{ height: '3rem' }} />
          </div>
        </div>
      </main>
    );
  }

  const settled = ticket.state === 'ACTIVE' || ticket.state === 'FAILED';

  return (
    <main>
      <h1 className="page-title">{ticket.jiraFields['summary'] ?? t.ticket}</h1>
      <p className="page-sub">{t.ticketViewTagline}</p>

      <div className="card">
        <div className="result">
          <div className="result__head">
            <span className={`result__tick${ticket.state === 'FAILED' ? ' is-failed' : ''}`}>
              {ticket.state === 'FAILED' ? <AlertIcon /> : <CheckIcon />}
            </span>
            <h2>{ticket.issueKey ?? (settled ? t.notInJira : t.beingCreated)}</h2>
          </div>

          <dl>
            <dt>{t.ticket}</dt>
            <dd>{ticket.ticketRef}</dd>
            <dt>{t.state}</dt>
            <dd>
              <span className="pill">{ticket.state}</span>
            </dd>
          </dl>

          <h3>{t.heldInVault}</h3>
          {ticket.parts.length > 0 ? (
            // Shown, not linked. The value is read here rather than arriving in a downloads
            // folder, which is the distinction the VIEW and DOWNLOAD grants exist to express.
            ticket.parts.map((part) => <SecuredPart key={part.contentRef} part={part} />)
          ) : (
            <p className="notice">
              <LockIcon />
              <span>{t.nothingSecured}</span>
            </p>
          )}

          <h3>{t.inJira}</h3>
          <dl>
            {Object.entries(ticket.jiraFields).map(([field, value]) => (
              <div key={field} style={{ display: 'contents' }}>
                <dt>{field}</dt>
                <dd className="ticketview__value">{value}</dd>
              </div>
            ))}
          </dl>
        </div>
      </div>
    </main>
  );
}
