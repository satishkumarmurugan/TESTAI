export type ParamLocation = 'PATH' | 'QUERY' | 'BODY' | 'HEADER';
export type ParamType = 'string' | 'integer' | 'number' | 'boolean';
export type AgentStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED';

export interface ParamDto {
  name: string;
  type: ParamType;
  location: ParamLocation;
  required: boolean;
  description: string;
}

export interface ToolDto {
  id?: number;
  name: string;
  description: string;
  httpMethod: string;
  contextPath: string;
  headersJson?: string | null;
  requiresConfirmation: boolean;
  params: ParamDto[];
}

export interface AgentDto {
  id?: number;
  name: string;
  description: string;
  baseUrl: string;
  status?: AgentStatus;
  tools: ToolDto[];
}

export interface ToolTrace {
  agent: string;
  tool: string;
  arguments: Record<string, unknown>;
  result: string;
}

export interface PendingTool {
  agent: string;
  tool: string;
  description: string;
  arguments: Record<string, unknown>;
}

export type StepStatus = 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'DONE' | 'FAILED' | 'REJECTED' | 'SKIPPED';

export interface StepView {
  step: number;
  agent: string;
  intent: string;
  status: StepStatus;
  result: string | null;
}

export interface PlanView {
  goal: string;
  steps: StepView[];
}

export interface ChatResponse {
  sessionId: string;
  status: 'DONE' | 'PENDING_CONFIRMATION';
  reply: string | null;
  pendingTool: PendingTool | null;
  toolCalls: ToolTrace[];
  plan: PlanView | null;
}

export type ChatMode = 'MANUAL' | 'AUTO';

export interface ChatRequest {
  sessionId: string | null;
  query: string;
  agentIds: number[];
  mode: ChatMode;
}
