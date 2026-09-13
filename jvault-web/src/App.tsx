import { useEffect, useState } from 'react';
import { ApiError, api } from './api/client';
import type { FormDefinition, IssueType, Project } from './api/types';
import { CreateIssueForm } from './components/CreateIssueForm';
import { AlertIcon, EmptyIcon } from './components/icons';

const DEPLOYMENT_ID = import.meta.env['VITE_DEPLOYMENT_ID'] ?? 'jira-cloud-dev';

type Connection = 'connecting' | 'live' | 'down';

export function App() {
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
        report(cause, setError);
      });
  }, []);

  useEffect(() => {
    setIssueTypes([]);
    setIssueTypeId('');
    setDefinition(null);
    if (projectKey !== '') {
      api.issueTypes(projectKey).then(setIssueTypes).catch((c) => report(c, setError));
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
      .catch((c) => report(c, setError))
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
          <span className={`dot dot--${connection === 'live' ? 'live' : connection === 'down' ? 'down' : ''}`} />
          {connection === 'live' ? 'Jira connected' : connection === 'down' ? 'Jira unreachable' : 'Connecting…'}
        </span>
      </header>

      <main>
        <h1 className="page-title">Create issue</h1>
        <p className="page-sub">
          The same fields Jira would ask for, plus where each answer is going to be kept.
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
                Project
              </label>
              <select
                id="project"
                value={projectKey}
                disabled={projects.length === 0}
                onChange={(event) => setProjectKey(event.target.value)}
              >
                <option value="">Choose…</option>
                {projects.map((project) => (
                  <option key={project.id} value={project.key}>
                    {project.name} ({project.key})
                  </option>
                ))}
              </select>
            </div>

            <div className="context__item">
              <label className="context__label" htmlFor="issuetype">
                Issue type
              </label>
              <select
                id="issuetype"
                value={issueTypeId}
                disabled={issueTypes.length === 0}
                onChange={(event) => setIssueTypeId(event.target.value)}
              >
                <option value="">Choose…</option>
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
            <FormSkeleton />
          ) : (
            <div className="empty">
              <EmptyIcon />
              <p>
                {projectKey === ''
                  ? 'Choose a project to begin.'
                  : 'Choose an issue type — the form is built from what Jira says it has.'}
              </p>
            </div>
          )}
        </div>
      </main>
    </>
  );
}

function FormSkeleton() {
  return (
    <div className="form-body" aria-busy="true" aria-label="Loading the form">
      {[68, 40, 55, 40, 48].map((width, index) => (
        <div className="field" key={index}>
          <div className="skeleton" style={{ width: `${width}%`, marginBottom: '0.5rem' }} />
          <div className="skeleton" style={{ height: '2.2rem' }} />
        </div>
      ))}
    </div>
  );
}

function report(cause: unknown, setError: (message: string) => void) {
  // Jira's own error bodies are not forwarded by the API, so there is nothing here that could
  // quote the request back at the user.
  setError(
    cause instanceof ApiError
      ? `${cause.message} (${cause.status})`
      : 'Could not reach jvault. Is the API running on port 8080?',
  );
}
