import { useEffect, useState } from 'react';
import { ApiError, api } from './api/client';
import type { FormDefinition, IssueType, Project } from './api/types';
import { CreateIssueForm } from './components/CreateIssueForm';

const DEPLOYMENT_ID = import.meta.env['VITE_DEPLOYMENT_ID'] ?? 'jira-cloud-prod';

export function App() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectKey, setProjectKey] = useState('');
  const [issueTypes, setIssueTypes] = useState<IssueType[]>([]);
  const [issueTypeId, setIssueTypeId] = useState('');
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.projects().then(setProjects).catch(reportInto(setError));
  }, []);

  useEffect(() => {
    setIssueTypes([]);
    setIssueTypeId('');
    setDefinition(null);
    if (projectKey !== '') {
      api.issueTypes(projectKey).then(setIssueTypes).catch(reportInto(setError));
    }
  }, [projectKey]);

  useEffect(() => {
    setDefinition(null);
    if (projectKey !== '' && issueTypeId !== '') {
      api.form(projectKey, issueTypeId).then(setDefinition).catch(reportInto(setError));
    }
  }, [projectKey, issueTypeId]);

  return (
    <main>
      <h1>Create issue</h1>

      {error ? (
        <p className="formError" role="alert">
          {error}
        </p>
      ) : null}

      <div className="chooser">
        <label htmlFor="project">Project</label>
        <select
          id="project"
          value={projectKey}
          onChange={(event) => setProjectKey(event.target.value)}
        >
          <option value="">—</option>
          {projects.map((project) => (
            <option key={project.id} value={project.key}>
              {project.name} ({project.key})
            </option>
          ))}
        </select>

        <label htmlFor="issuetype">Type</label>
        <select
          id="issuetype"
          value={issueTypeId}
          disabled={issueTypes.length === 0}
          onChange={(event) => setIssueTypeId(event.target.value)}
        >
          <option value="">—</option>
          {issueTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </select>
      </div>

      {definition ? (
        <CreateIssueForm
          key={`${definition.projectKey}/${definition.issueTypeId}`}
          definition={definition}
          deploymentId={DEPLOYMENT_ID}
        />
      ) : null}
    </main>
  );
}

function reportInto(setError: (message: string) => void) {
  return (cause: unknown) => {
    // Jira's own error bodies are not forwarded by the API, so there is nothing here that could
    // quote the request back at the user.
    setError(cause instanceof ApiError ? cause.message : 'Could not reach jvault.');
  };
}
