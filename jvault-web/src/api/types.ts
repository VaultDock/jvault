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
  | 'USER';

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
