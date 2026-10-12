import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AgentDto, AgentStatus, ChatRequest, ChatResponse } from './models';

// Backend base URL. Change for other environments.
export const API_BASE = 'http://localhost:8080/api';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  listAgents(status?: AgentStatus): Observable<AgentDto[]> {
    const q = status ? `?status=${status}` : '';
    return this.http.get<AgentDto[]>(`${API_BASE}/agents${q}`);
  }

  createAgent(agent: AgentDto): Observable<AgentDto> {
    return this.http.post<AgentDto>(`${API_BASE}/agents`, agent);
  }

  setStatus(id: number, value: AgentStatus): Observable<AgentDto> {
    return this.http.patch<AgentDto>(`${API_BASE}/agents/${id}/status?value=${value}`, {});
  }

  deleteAgent(id: number): Observable<void> {
    return this.http.delete<void>(`${API_BASE}/agents/${id}`);
  }

  chat(req: ChatRequest, serviceToken: string): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${API_BASE}/chat`, req, { headers: this.headers(serviceToken) });
  }

  confirm(sessionId: string, approve: boolean, serviceToken: string): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(
      `${API_BASE}/chat/confirm`,
      { sessionId, approve },
      { headers: this.headers(serviceToken) }
    );
  }

  private headers(serviceToken: string): HttpHeaders {
    return serviceToken ? new HttpHeaders({ 'X-Service-Token': serviceToken }) : new HttpHeaders();
  }
}

/** Extracts a readable message from a backend error response. */
export function errorMessage(e: unknown): string {
  const err = e as { error?: { message?: string }; message?: string; status?: number };
  if (err?.status === 0) return 'Cannot reach the backend at ' + API_BASE;
  return err?.error?.message ?? err?.message ?? 'Unexpected error';
}
