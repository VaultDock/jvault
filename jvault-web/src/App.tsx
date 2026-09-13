import { useEffect, useState } from 'react';
import { ApiError, api } from './api/client';
import type { FormDefinition, Identity, IssueType, Project } from './api/types';
import { CreateIssueForm } from './components/CreateIssueForm';
import { AlertIcon, EmptyIcon } from './components/icons';
import { LANGUAGES, TranslationProvider, pickLanguage, useT, type Language } from './i18n';

const DEPLOYMENT_ID = import.meta.env['VITE_DEPLOYMENT_ID'] ?? 'jira-cloud-dev';

type Connection = 'connecting' | 'live' | 'down';

/**
 * The language is decided before anything is rendered, because Jira's own account locale is one
 * of the inputs to it and that takes a round trip.
 */
export function App() {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    api
      .me()
      .then(setIdentity)
      // Not fatal. An unreachable API is reported by the page itself; falling back to the
      // browser's language is better than showing nothing while deciding what to call it.
      .catch(() => setIdentity(null))
      .finally(() => setReady(true));
  }, []);

  if (!ready) {
    return null;
  }
  return (
    <TranslationProvider jiraLocale={identity?.jiraLocale ?? null}>
      <CreateIssuePage jiraLocale={identity?.jiraLocale ?? null} />
    </TranslationProvider>
  );
}

function CreateIssuePage({ jiraLocale }: { jiraLocale: string | null }) {
  const { t, language, setLanguage } = useT();

  // Jira returns field names in the language of the account jvault authenticates as, and ignores
  // Accept-Language on the createmeta endpoints. Reading German chrome around English field
  // labels looks like a half-finished translation unless somebody says why.
  const labelLanguage = pickLanguage(null, jiraLocale, []);
  const labelsDiffer = labelLanguage !== language;
  const [projects, setProjects] = useState<Project[]>([]);
  const [connection, setConnection] = useState<Connection>('connecting');
  const [projectKey, setProjectKey] = useState('');
  const [issueTypes, setIssueTypes] = useState<IssueType[]>([]);
  const [issueTypeId, setIssueTypeId] = useState('');
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [loadingForm, setLoadingForm] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .projects()
      .then((found) => {
        setProjects(found);
        setConnection('live');
        // One project is the common case in practice, and making someone choose from a list of
        // one is a step that exists only to be completed.
        if (found.length === 1 && found[0]) {
          setProjectKey(found[0].key);
        }
      })
      .catch((cause) => {
        setConnection('down');
        report(cause, setError, t.errorUnreachable);
      });
    // The strings are read at the moment of failure; re-running this on a language change would
    // mean re-querying Jira to re-word an error.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    setIssueTypes([]);
    setIssueTypeId('');
    setDefinition(null);
    if (projectKey !== '') {
      api.issueTypes(projectKey).then(setIssueTypes).catch((c) => report(c, setError, t.errorUnreachable));
    }
  }, [projectKey]);

  useEffect(() => {
    setDefinition(null);
    if (projectKey === '' || issueTypeId === '') {
      return;
    }
    setLoadingForm(true);
    api
      .form(projectKey, issueTypeId)
      .then(setDefinition)
      .catch((c) => report(c, setError, t.errorUnreachable))
      .finally(() => setLoadingForm(false));
  }, [projectKey, issueTypeId]);

  return (
    <>
      <header className="topbar">
        <span className="topbar__mark" aria-hidden="true">
          jv
        </span>
        <span className="topbar__name">jvault</span>
        <span className="topbar__status">
          <span
            className={`dot dot--${
              connection === 'live' ? 'live' : connection === 'down' ? 'down' : ''
            }`}
          />
          {connection === 'live' ? t.connected : connection === 'down' ? t.unreachable : t.connecting}
        </span>

        <label className="topbar__lang" title={t.languageNote}>
          <span className="visually-hidden">{t.language}</span>
          <select value={language} onChange={(event) => setLanguage(event.target.value as Language)}>
            {Object.entries(LANGUAGES).map(([code, name]) => (
              <option key={code} value={code}>
                {name}
              </option>
            ))}
          </select>
        </label>
      </header>

      <main>
        <h1 className="page-title">{t.createIssue}</h1>
        <p className="page-sub">
          {t.tagline}
          {labelsDiffer ? <> {t.labelsFrom(LANGUAGES[labelLanguage])}</> : null}
        </p>

        {error ? (
          <p className="notice notice--error" role="alert">
            <AlertIcon />
            <span>{error}</span>
          </p>
        ) : null}

        <div className="card">
          <div className="context">
            <div className="context__item">
              <label className="context__label" htmlFor="project">
                {t.project}
              </label>
              <select
                id="project"
                value={projectKey}
                disabled={projects.length === 0}
                onChange={(event) => setProjectKey(event.target.value)}
              >
                <option value="">{t.choose}</option>
                {projects.map((project) => (
                  <option key={project.id} value={project.key}>
                    {project.name} ({project.key})
                  </option>
                ))}
              </select>
            </div>

            <div className="context__item">
              <label className="context__label" htmlFor="issuetype">
                {t.issueType}
              </label>
              <select
                id="issuetype"
                value={issueTypeId}
                disabled={issueTypes.length === 0}
                onChange={(event) => setIssueTypeId(event.target.value)}
              >
                <option value="">{t.choose}</option>
                {issueTypes.map((type) => (
                  <option key={type.id} value={type.id}>
                    {type.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {definition ? (
            <CreateIssueForm
              key={`${definition.projectKey}/${definition.issueTypeId}`}
              definition={definition}
              deploymentId={DEPLOYMENT_ID}
            />
          ) : loadingForm ? (
            <FormSkeleton label={t.loadingForm} />
          ) : (
            <div className="empty">
              <EmptyIcon />
              <p>
                {projectKey === '' ? t.chooseProject : t.chooseIssueType}
              </p>
            </div>
          )}
        </div>
      </main>
    </>
  );
}

function FormSkeleton({ label }: { label: string }) {
  return (
    <div className="form-body" aria-busy="true" aria-label={label}>
      {[68, 40, 55, 40, 48].map((width, index) => (
        <div className="field" key={index}>
          <div className="skeleton" style={{ width: `${width}%`, marginBottom: '0.5rem' }} />
          <div className="skeleton" style={{ height: '2.2rem' }} />
        </div>
      ))}
    </div>
  );
}

function report(cause: unknown, setError: (message: string) => void, unreachable: string) {
  // Jira's own error bodies are not forwarded by the API, so there is nothing here that could
  // quote the request back at the user.
  setError(cause instanceof ApiError ? `${cause.message} (${cause.status})` : unreachable);
}
