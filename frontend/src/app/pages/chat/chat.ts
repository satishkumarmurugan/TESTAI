import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { JsonPipe } from '@angular/common';
import { Observable } from 'rxjs';
import { ApiService, errorMessage } from '../../api.service';
import { AgentDto, ChatMode, ChatResponse, PendingTool, PlanView, ToolTrace } from '../../models';

interface ChatMsg {
  role: 'user' | 'assistant' | 'tool' | 'error';
  text: string;
  trace?: ToolTrace;
}

@Component({
  selector: 'app-chat',
  imports: [FormsModule, JsonPipe],
  template: `
    <div class="chat-layout">
      <aside class="panel agents-panel">
        <h3>Mode</h3>
        <div class="seg">
          <button type="button" [class.on]="mode() === 'MANUAL'" (click)="setMode('MANUAL')">Manual</button>
          <button type="button" [class.on]="mode() === 'AUTO'" (click)="setMode('AUTO')">Auto</button>
        </div>
        <p class="muted small">
          @if (mode() === 'AUTO') {
            The assistant sets a goal, plans the steps and picks the agent for each one.
            Ticked agents are the ones it may use.
          } @else {
            One assistant uses the agents you tick below.
          }
        </p>

        <h3>Agents</h3>
        @for (a of agents(); track a.id) {
          <label class="agent-item" [class.selected]="isSelected(a.id!)">
            <input type="checkbox" [checked]="isSelected(a.id!)" (change)="toggle(a.id!)" />
            <div>
              <div class="agent-name">{{ a.name }}</div>
              <div class="muted small">{{ a.description }}</div>
              <div class="chips">
                @for (t of a.tools; track t.id) {
                  <span class="chip" [class.warn]="t.requiresConfirmation"
                        [title]="t.description + (t.requiresConfirmation ? ' (asks for confirmation)' : '')">
                    {{ t.name }}
                  </span>
                }
              </div>
            </div>
          </label>
        } @empty {
          <p class="muted">No active agents. Onboard a service first.</p>
        }

        <hr />
        <label class="small muted" for="tok">Service token (optional)</label>
        <input id="tok" type="password" [(ngModel)]="token" placeholder="Bearer token for target services" />
        <button class="link small" (click)="newChat()">New conversation</button>
      </aside>

      <section class="panel chat-panel">
        <div class="messages" #scroller>
          @if (messages().length === 0) {
            <div class="muted empty">
              @if (mode() === 'AUTO') {
                Describe what you want done, e.g.<br />
                <em>"Create user ravi.k in AppDesigner and email him that his account is ready"</em><br />
                I will plan it and choose the agents.
              } @else {
                Pick one or more agents, then ask something, e.g.<br />
                <em>"Create user ravi.k in AppDesigner and email him that his account is ready"</em>
              }
            </div>
          }
          @for (m of messages(); track $index) {
            @if (m.role === 'tool') {
              <details class="bubble tool">
                <summary>Called <b>{{ m.trace!.agent }}.{{ m.trace!.tool }}</b></summary>
                <pre>{{ m.trace!.arguments | json }}</pre>
                <pre class="result">{{ m.trace!.result }}</pre>
              </details>
            } @else {
              <div class="bubble" [class]="m.role">{{ m.text }}</div>
            }
          }
          @if (busy()) {
            <div class="bubble assistant muted">{{ mode() === 'AUTO' ? 'Planning and running…' : 'Thinking…' }}</div>
          }
        </div>

        @if (plan(); as p) {
          <div class="plan">
            <div class="plan-goal"><b>Goal:</b> {{ p.goal }}</div>
            @for (s of p.steps; track s.step) {
              <div class="plan-step">
                <span class="step-no">{{ s.step }}</span>
                <span class="chip">{{ s.agent }}</span>
                <span class="step-intent" [title]="s.result || ''">{{ s.intent }}</span>
                <span class="status" [class]="s.status">{{ s.status.replace('_', ' ') }}</span>
              </div>
            }
          </div>
        }

        @if (pending(); as p) {
          <div class="confirm">
            <div><b>Approve this action?</b></div>
            <div><b>{{ p.agent }}.{{ p.tool }}</b> — {{ p.description }}</div>
            <pre>{{ p.arguments | json }}</pre>
            <div class="row">
              <button class="primary" (click)="decide(true)">Approve</button>
              <button (click)="decide(false)">Reject</button>
            </div>
          </div>
        }

        <div class="composer">
          <input [(ngModel)]="query" (keyup.enter)="send()"
                 [disabled]="busy() || !!pending()" placeholder="Ask something…" />
          <button class="primary" (click)="send()" [disabled]="busy() || !!pending() || !query.trim()">Send</button>
        </div>
      </section>
    </div>
  `
})
export class ChatComponent implements OnInit {
  private api = inject(ApiService);
  @ViewChild('scroller') scroller?: ElementRef<HTMLDivElement>;

  agents = signal<AgentDto[]>([]);
  selected = signal<Set<number>>(new Set());
  messages = signal<ChatMsg[]>([]);
  pending = signal<PendingTool | null>(null);
  plan = signal<PlanView | null>(null);
  mode = signal<ChatMode>('MANUAL');
  busy = signal(false);

  query = '';
  token = '';
  private sessionId: string | null = null;

  ngOnInit(): void {
    this.api.listAgents('ACTIVE').subscribe({
      next: a => {
        this.agents.set(a);
        this.selected.set(new Set(a.map(x => x.id!)));
      },
      error: e => this.push({ role: 'error', text: errorMessage(e) })
    });
  }

  isSelected(id: number): boolean {
    return this.selected().has(id);
  }

  toggle(id: number): void {
    const s = new Set(this.selected());
    if (s.has(id)) s.delete(id);
    else s.add(id);
    this.selected.set(s);
  }

  setMode(m: ChatMode): void {
    if (m === this.mode() || this.busy()) return;
    this.mode.set(m);
    this.newChat(); // sessions are mode specific
  }

  newChat(): void {
    this.sessionId = null;
    this.messages.set([]);
    this.pending.set(null);
    this.plan.set(null);
  }

  send(): void {
    const q = this.query.trim();
    if (!q || this.busy() || this.pending()) return;
    if (this.mode() === 'MANUAL' && this.selected().size === 0) {
      this.push({ role: 'error', text: 'Select at least one agent first.' });
      return;
    }
    this.push({ role: 'user', text: q });
    this.query = '';
    this.plan.set(null);
    this.run(
      this.api.chat(
        { sessionId: this.sessionId, query: q, agentIds: [...this.selected()], mode: this.mode() },
        this.token
      )
    );
  }

  decide(approve: boolean): void {
    if (!this.sessionId) return;
    this.pending.set(null);
    this.push({ role: 'user', text: approve ? '✔ Approved' : '✖ Rejected' });
    this.run(this.api.confirm(this.sessionId, approve, this.token));
  }

  private run(call: Observable<ChatResponse>): void {
    this.busy.set(true);
    call.subscribe({
      next: r => {
        this.sessionId = r.sessionId;
        this.plan.set(r.plan ?? null);
        for (const t of r.toolCalls ?? []) {
          this.push({ role: 'tool', text: `${t.agent}.${t.tool}`, trace: t });
        }
        if (r.status === 'PENDING_CONFIRMATION') this.pending.set(r.pendingTool);
        else this.push({ role: 'assistant', text: r.reply || '(no reply)' });
        this.busy.set(false);
      },
      error: e => {
        this.push({ role: 'error', text: errorMessage(e) });
        this.busy.set(false);
      }
    });
  }

  private push(m: ChatMsg): void {
    this.messages.update(list => [...list, m]);
    setTimeout(() => {
      const el = this.scroller?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
