import type {
  CreateTicketRequest,
  FormDefinition,
  Identity,
  IssueType,
  Problem,
  Project,
  TicketResponse,
  UploadedAttachment,
  UserRef,
} from './types';

/**
 * Everything the SPA knows about the outside world.
 *
 * <p>There is no Jira client here, and that is the point rather than an omission. The browser
 * talking to Jira directly would mean the browser deciding what may go there, and the whole
 * design rests on that decision being made in one place that the user cannot reach.
 */
export class ApiError extends Error {
  constructor(
    readonly problem: Problem,
    readonly status: number,
  ) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
  }

  /** Field-level errors, keyed by field, for rendering beside the control that caused them. */
  fieldErrors(): Map<string, string> {
    const errors = new Map<string, string>();
    for (const error of this.problem.errors ?? []) {
      // The API names nested fields as `fields.summary`; the form knows them as `summary`.
      errors.set(error.field.replace(/^fields\./, ''), error.message ?? error.code);
    }
    return errors;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...init?.headers },
  });

  if (!response.ok) {
    throw new ApiError(await problemFrom(response), response.status);
  }
  return (await response.json()) as T;
}

async function problemFrom(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem;
  } catch {
    // A gateway or a proxy answering in HTML. Reporting its body would be noise.
    return { title: 'The request failed', status: response.status };
  }
}

export const api = {
  me: () => request<Identity>('/api/v1/meta/me'),

  projects: () => request<Project[]>('/api/v1/meta/projects'),

  searchUsers: (projectKey: string, query: string, assignable: boolean) =>
    request<UserRef[]>(
      `/api/v1/meta/projects/${encodeURIComponent(projectKey)}/users` +
        `?query=${encodeURIComponent(query)}&assignable=${assignable}`,
    ),

  issueTypes: (projectKey: string) =>
    request<IssueType[]>(`/api/v1/meta/projects/${encodeURIComponent(projectKey)}/issuetypes`),

  form: (projectKey: string, issueTypeId: string) =>
    request<FormDefinition>(
      `/api/v1/meta/projects/${encodeURIComponent(projectKey)}` +
        `/issuetypes/${encodeURIComponent(issueTypeId)}/fields`,
    ),

  /**
   * Creates a ticket.
   *
   * The idempotency key is generated per attempt and reused across retries of that attempt, so a
   * network failure the browser retries cannot produce two issues. A fresh submission is a new
   * key, because a user pressing Create twice on purpose means it.
   */
  /**
   * Uploads one document to a ticket that already exists.
   *
   * No Content-Type header: the browser sets it, and it must include the multipart boundary it
   * generated. Setting it by hand produces a body the server cannot parse.
   */
  uploadAttachment: (ticketRef: string, file: File) => {
    const body = new FormData();
    body.append('file', file);
    return request<UploadedAttachment>(
      `/api/v1/tickets/${encodeURIComponent(ticketRef)}/attachments`,
      { method: 'POST', body },
    );
  },

  createTicket: (body: CreateTicketRequest, idempotencyKey: string) =>
    request<TicketResponse>('/api/v1/tickets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }),
};
