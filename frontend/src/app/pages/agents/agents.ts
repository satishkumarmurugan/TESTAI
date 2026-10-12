import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../api.service';
import { AgentDto } from '../../models';

@Component({
  selector: 'app-agents',
  imports: [RouterLink],
  template: `
    <div class="row between">
      <h2>Agents</h2>
      <a class="btn primary" routerLink="/agents/new">+ Onboard service</a>
    </div>

    @if (error()) { <div class="alert">{{ error() }}</div> }

    @for (a of agents(); track a.id) {
      <div class="panel agent-card">
        <div class="row between">
          <div>
            <span class="agent-name">{{ a.name }}</span>
            <span class="badge" [class]="a.status">{{ a.status }}</span>
            <div class="muted small">{{ a.baseUrl }}</div>
            <div>{{ a.description }}</div>
          </div>
          <div class="row">
            @if (a.status === 'ACTIVE') {
              <button (click)="setStatus(a, 'DISABLED')">Disable</button>
            } @else {
              <button (click)="setStatus(a, 'ACTIVE')">Enable</button>
            }
            <button class="danger" (click)="remove(a)">Delete</button>
          </div>
        </div>

        <details>
          <summary>{{ a.tools.length }} tool(s)</summary>
          <table>
            <thead><tr><th>Tool</th><th>Call</th><th>Parameters</th><th>Confirm</th></tr></thead>
            <tbody>
              @for (t of a.tools; track t.id) {
                <tr>
                  <td><b>{{ t.name }}</b><div class="muted small">{{ t.description }}</div></td>
                  <td><code>{{ t.httpMethod }} {{ t.contextPath }}</code></td>
                  <td>
                    @for (p of t.params; track p.name) {
                      <span class="chip" [title]="p.description">
                        {{ p.name }}<span class="muted"> {{ p.location }}{{ p.required ? '*' : '' }}</span>
                      </span>
                    }
                  </td>
                  <td>{{ t.requiresConfirmation ? 'Yes' : 'No' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </details>
      </div>
    } @empty {
      <p class="muted">No agents yet.</p>
    }
  `
})
export class AgentsComponent implements OnInit {
  private api = inject(ApiService);
  agents = signal<AgentDto[]>([]);
  error = signal('');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.listAgents().subscribe({
      next: a => this.agents.set(a),
      error: e => this.error.set(errorMessage(e))
    });
  }

  setStatus(a: AgentDto, status: 'ACTIVE' | 'DISABLED'): void {
    this.api.setStatus(a.id!, status).subscribe({
      next: () => this.load(),
      error: e => this.error.set(errorMessage(e))
    });
  }

  remove(a: AgentDto): void {
    if (!confirm(`Delete agent "${a.name}" and all its tools?`)) return;
    this.api.deleteAgent(a.id!).subscribe({
      next: () => this.load(),
      error: e => this.error.set(errorMessage(e))
    });
  }
}
