import { useState } from 'react';
import { ApiError, api } from '../api/client';
import type { AuthStatus } from '../api/types';
import { useT } from '../i18n';
import { AlertIcon, LockIcon } from './icons';

/**
 * The way in.
 *
 * <p>Whichever methods the deployment offers, in the order it offers them. Atlassian first where
 * it is available, because it is the one where jvault never handles a Jira credential; the token
 * form second and visually quieter, because it is the fallback for a deployment that cannot
 * reach auth.atlassian.com and it gives real things up (docs/10-authentication.md 10.6).
 */
export function SignIn({ auth, reason }: { auth: AuthStatus; reason: string | null }) {
  const { t } = useT();
  const [showToken, setShowToken] = useState(auth.methods.length === 1
    && auth.methods[0] === 'MANUAL_TOKEN');

  const atlassian = auth.methods.includes('ATLASSIAN') && auth.loginUrl;
  const manual = auth.methods.includes('MANUAL_TOKEN');

  return (
    <main className="signin">
      <div className="card signin__card">
        <span className="signin__mark" aria-hidden="true">
          jv
        </span>
        <h1>{t.signInTitle}</h1>
        <p className="page-sub">{t.signInBlurb}</p>

        {reason ? (
          <p className="notice notice--error" role="alert">
            <AlertIcon />
            <span>{signInMessage(reason, t)}</span>
          </p>
        ) : null}

        {atlassian ? (
          <>
            <a className="btn-primary signin__button" href={auth.loginUrl!}>
              {t.signInWithAtlassian}
            </a>
            <p className="signin__note">{t.signInNote}</p>
          </>
        ) : null}

        {manual && atlassian && !showToken ? (
          <button type="button" className="linklike signin__alt"
                  onClick={() => setShowToken(true)}>
            {t.signInWithToken}
          </button>
        ) : null}

        {manual && (showToken || !atlassian) ? <TokenForm standalone={!atlassian} /> : null}

        {!atlassian && !manual ? (
          <p className="notice">
            <LockIcon />
            <span>{t.signInUnavailable}</span>
          </p>
        ) : null}
      </div>
    </main>
  );
}

/**
 * Signing in by pasting an API token.
 *
 * <p>The token is held in this component and nowhere else: not in local storage, not in a URL,
 * and cleared the moment it has been sent. The field is a password field so that it is not read
 * over a shoulder or captured by a screen recording, which is a real way these leak.
 */
function TokenForm({ standalone }: { standalone: boolean }) {
  const { t } = useT();
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.importToken(email.trim(), token.trim());
      setToken('');
      // Reloaded rather than routed: the session cookie is now set, and everything the page
      // decided while signed out needs deciding again.
      window.location.assign('/');
    } catch (cause) {
      setToken('');
      setError(cause instanceof ApiError ? tokenMessage(cause, t) : t.signInFailed);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={`signin__token${standalone ? '' : ' signin__token--secondary'}`}
          onSubmit={submit}>
      {standalone ? null : <hr className="signin__rule" />}

      <p className="signin__tradeoff">
        <LockIcon />
        <span>{t.tokenTradeoff}</span>
      </p>

      <label htmlFor="signin-email">{t.tokenEmail}</label>
      <input id="signin-email" type="email" autoComplete="username" required
             value={email} onChange={(event) => setEmail(event.target.value)} />

      <label htmlFor="signin-token">{t.tokenLabel}</label>
      <input id="signin-token" type="password" autoComplete="off" required
             value={token} onChange={(event) => setToken(event.target.value)} />

      {error ? (
        <p className="field__error" role="alert">
          {error}
        </p>
      ) : null}

      <button type="submit" className="btn-primary" disabled={busy || !email || !token}>
        {busy ? t.signingIn : t.signInTitle}
      </button>

      <p className="signin__note">{t.tokenWhere}</p>
    </form>
  );
}

function tokenMessage(error: ApiError, t: ReturnType<typeof useT>['t']): string {
  switch (error.problem.code) {
    case 'credential-invalid':
      return t.tokenInvalid;
    case 'credential-too-powerful':
      return t.tokenTooPowerful;
    case 'jira-unreachable':
      return t.tokenJiraUnreachable;
    default:
      return error.message;
  }
}

function signInMessage(reason: string, t: ReturnType<typeof useT>['t']): string {
  switch (reason) {
    case 'declined':
      return t.signInDeclined;
    case 'expired':
      return t.signInExpired;
    case 'nosites':
      return t.signInNoSites;
    case 'exchange':
    case 'scopes':
      return t.signInMisconfigured;
    default:
      return t.signInFailed;
  }
}
