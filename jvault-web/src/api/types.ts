export type Placement = 'JIRA' | 'EXTERNAL' | 'BOTH';

export type SupportLevel = 'REPRODUCED' | 'DELEGATED_VALIDATION' | 'READ_ONLY';

export type Control =
  | 'TEXT'
  | 'RICH_TEXT'
  | 'NUMBER'
  | 'DATE'
  | 'SELECT'
  | 'MULTI_SELECT'
  | 'LABELS'
  | 'USER'
  | 'ISSUE';

export interface AllowedValue {
  id: string;
  value: string;
}

export interface FormField {
  key: string;
  name: string;
  required: boolean;
  schemaType: string | null;
  customType: string | null;
  allowedValues: AllowedValue[];
  hasMoreOptions: boolean;
  /** Where this field's value will be stored — known before the user types into it. */
  placement: Placement;
  classification: string | null;
  allowOverride: boolean;
  supportLevel: SupportLevel;
  control: Control;
}

export interface FormDefinition {
  projectKey: string;
  issueTypeId: string;
  fields: FormField[];
}

export interface Project {
  id: string;
  key: string;
  name: string;
  style: string | null;
}

export interface IssueType {
  id: string;
  name: string;
  subtask: boolean;
  description: string | null;
}

export interface CreateTicketRequest {
  deploymentId: string;
  projectKey: string;
  issueTypeId: string;
  fields: Record<string, string>;
  placementOverrides?: Record<string, Placement>;
  dedupeKey?: string;
  correlationId?: string;
}

export interface TicketPart {
  contentRef: string;
  partType: string;
  fieldKey: string | null;
  classification: string;
  mediaType: string | null;
  link: string;
}

export interface TicketResponse {
  ticketRef: string;
  state: string;
  issueKey: string | null;
  jiraFields: Record<string, string>;
  parts: TicketPart[];
}

/** RFC 9457, which is what the API returns for every failure. */
export interface Problem {
  title: string;
  detail?: string;
  status: number;
  code?: string;
  errors?: { field: string; code: string; message?: string }[];
}

export interface UserRef {
  accountId: string;
  displayName: string;
  email: string | null;
  active: boolean;
}

/** Who jvault thinks you are, and which language Jira's own labels arrive in. */
export interface Identity {
  user: string;
  jiraAccount: string | null;
  jiraLocale: string | null;
  deployment: string;
}

/** Whether anybody is signed in, and how signing in works here. */
export interface AuthStatus {
  authenticated: boolean;
  displayName: string | null;
  email: string | null;
  /** The ways in this deployment offers, in the order to show them. */
  methods: ('ATLASSIAN' | 'MANUAL_TOKEN' | 'DEV')[];
  loginUrl: string | null;
}

export interface UploadedAttachment {
  contentRef: string;
  fileName: string;
  sizeBytes: number;
  mediaType: string;
  classification: string;
  jiraSurrogate: string;
  link: string;
}

export interface IssueRef {
  key: string;
  summary: string | null;
  issueTypeName: string | null;
}

/** Content rendered for reading rather than for keeping. */
export interface RenderedContent {
  kind: 'RICH_TEXT' | 'TEXT' | 'UNSUPPORTED' | 'TOO_LARGE';
  mediaType: string | null;
  sizeBytes: number;
  text: string | null;
  document: unknown;
}
