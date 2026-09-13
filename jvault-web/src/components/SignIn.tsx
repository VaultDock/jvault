import { useT } from '../i18n';
import { LockIcon } from './icons';

/**
 * The sign-in screen.
 *
 * <p>One button, because there is one way in. Sending somebody to Atlassian rather than asking
 * for a password here is the point: jvault never sees a Jira credential, and what comes back is
 * an authorization carrying that person's own Jira permissions.
 */
export function SignIn({ loginUrl, reason }: { loginUrl: string; reason: string | null }) {
  const { t } = useT();

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
            <LockIcon />
            <span>{signInMessage(reason, t)}</span>
          </p>
        ) : null}

        <a className="btn-primary signin__button" href={loginUrl}>
          {t.signInWithAtlassian}
        </a>

        <p className="signin__note">{t.signInNote}</p>
      </div>
    </main>
  );
}

function signInMessage(reason: string, t: ReturnType<typeof useT>['t']): string {
  switch (reason) {
    case 'declined':
      return t.signInDeclined;
    case 'expired':
      return t.signInExpired;
    case 'nosites':
      return t.signInNoSites;
    default:
      return t.signInFailed;
  }
}
