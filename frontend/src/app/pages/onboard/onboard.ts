import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, errorMessage } from '../../api.service';
import { AgentDto } from '../../models';

@Component({
  selector: 'app-onboard',
  imports: [ReactiveFormsModule],
  template: `
    <div class="row between">
      <h2>Onboard a service</h2>
      <button type="button" (click)="loadExample()">Fill example</button>
    </div>
    <p class="muted">
      A service becomes an agent. Each tool is one API call the assistant can make.
      Use <code>{{ '{' }}name{{ '}' }}</code> placeholders in the path and add a matching PATH parameter.
    </p>

    @if (error()) { <div class="alert">{{ error() }}</div> }

    <form [formGroup]="form" (ngSubmit)="submit()">
      <div class="panel">
        <h3>Service</h3>
        <div class="grid2">
          <div>
            <label>Agent name</label>
            <input formControlName="name" placeholder="kawach" />
            <div class="muted small">Letters, digits, underscore.</div>
          </div>
          <div>
            <label>Base URL</label>
            <input formControlName="baseUrl" placeholder="https://kawach.example.internal/api" />
          </div>
        </div>
        <label>Description</label>
        <textarea formControlName="description" rows="2"
                  placeholder="What does this service do? The assistant reads this."></textarea>
      </div>

      <div formArrayName="tools">
        @for (tool of tools.controls; track tool; let ti = $index) {
          <div class="panel" [formGroupName]="ti">
            <div class="row between">
              <h3>Tool {{ ti + 1 }}</h3>
              <button type="button" class="danger" (click)="removeTool(ti)">Remove tool</button>
            </div>
            <div class="grid2">
              <div>
                <label>Tool name</label>
                <input formControlName="name" placeholder="createUser" />
              </div>
              <div class="row">
                <div class="grow">
                  <label>Method</label>
                  <select formControlName="httpMethod">
                    @for (m of methods; track m) { <option [value]="m">{{ m }}</option> }
                  </select>
                </div>
                <div class="grow3">
                  <label>Context path</label>
                  <input formControlName="contextPath" placeholder="/{{ '{' }}realm{{ '}' }}/create-user" />
                </div>
              </div>
            </div>
            <label>Description</label>
            <input formControlName="description" placeholder="Create a user in an application realm." />

            <label class="check">
              <input type="checkbox" formControlName="requiresConfirmation" />
              Ask the user to approve before running (recommended for create/update/delete)
            </label>

            <div class="row between">
              <h4>Parameters</h4>
              <button type="button" (click)="addParam(ti)">+ Parameter</button>
            </div>
            <div formArrayName="params">
              @for (p of params(ti).controls; track p; let pi = $index) {
                <div class="param-row" [formGroupName]="pi">
                  <input formControlName="name" placeholder="name" />
                  <select formControlName="location">
                    @for (l of locations; track l) { <option [value]="l">{{ l }}</option> }
                  </select>
                  <select formControlName="type">
                    @for (t of types; track t) { <option [value]="t">{{ t }}</option> }
                  </select>
                  <label class="check inline"><input type="checkbox" formControlName="required" /> required</label>
                  <input class="grow3" formControlName="description" placeholder="description for the assistant" />
                  <button type="button" class="danger" (click)="removeParam(ti, pi)">✕</button>
                </div>
              }
            </div>
          </div>
        }
      </div>

      <div class="row">
        <button type="button" (click)="addTool()">+ Add tool</button>
        <span class="grow"></span>
        <button type="submit" class="primary" [disabled]="saving()">Save agent</button>
      </div>
    </form>
  `
})
export class OnboardComponent {
  private fb = inject(FormBuilder);
  private api = inject(ApiService);
  private router = inject(Router);

  methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
  locations = ['PATH', 'QUERY', 'BODY', 'HEADER'];
  types = ['string', 'integer', 'number', 'boolean'];

  error = signal('');
  saving = signal(false);

  form = this.fb.group({
    name: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_]{1,30}$/)]],
    description: [''],
    baseUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
    tools: this.fb.array<FormGroup>([])
  });

  constructor() {
    this.addTool();
  }

  get tools(): FormArray<FormGroup> {
    return this.form.controls.tools;
  }

  params(ti: number): FormArray<FormGroup> {
    return this.tools.at(ti).get('params') as FormArray<FormGroup>;
  }

  addTool(): void {
    this.tools.push(this.toolGroup());
  }

  removeTool(ti: number): void {
    this.tools.removeAt(ti);
  }

  addParam(ti: number): void {
    this.params(ti).push(this.paramGroup());
  }

  removeParam(ti: number, pi: number): void {
    this.params(ti).removeAt(pi);
  }

  submit(): void {
    this.error.set('');
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Please fix the highlighted fields: agent name, base URL and every tool need valid values.');
      return;
    }
    this.saving.set(true);
    this.api.createAgent(this.form.getRawValue() as unknown as AgentDto).subscribe({
      next: () => this.router.navigateByUrl('/agents'),
      error: e => {
        this.error.set(errorMessage(e));
        this.saving.set(false);
      }
    });
  }

  loadExample(): void {
    this.tools.clear();
    this.form.patchValue({
      name: 'notification',
      baseUrl: 'http://localhost:8080/mock/notify',
      description: 'Sends email and SMS notifications and checks delivery status.'
    });

    const email = this.toolGroup();
    email.patchValue({
      name: 'sendEmail', description: 'Send an email to one recipient.',
      httpMethod: 'POST', contextPath: '/email/send', requiresConfirmation: true
    });
    this.fill(email, [
      ['to', 'BODY', true, 'Recipient email address'],
      ['subject', 'BODY', true, 'Email subject'],
      ['message', 'BODY', true, 'Email body text']
    ]);

    const status = this.toolGroup();
    status.patchValue({
      name: 'getStatus', description: 'Get delivery status of a notification by its id.',
      httpMethod: 'GET', contextPath: '/status/{notificationId}', requiresConfirmation: false
    });
    this.fill(status, [['notificationId', 'PATH', true, 'Id returned when the notification was sent']]);

    this.tools.push(email);
    this.tools.push(status);
  }

  private fill(tool: FormGroup, rows: [string, string, boolean, string][]): void {
    const arr = tool.get('params') as FormArray<FormGroup>;
    for (const [name, location, required, description] of rows) {
      const g = this.paramGroup();
      g.patchValue({ name, location, required, description });
      arr.push(g);
    }
  }

  private toolGroup(): FormGroup {
    return this.fb.group({
      name: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_]{1,30}$/)]],
      description: ['', Validators.required],
      httpMethod: ['POST', Validators.required],
      contextPath: ['/', [Validators.required, Validators.pattern(/^\/.*/)]],
      requiresConfirmation: [true],
      params: this.fb.array<FormGroup>([])
    });
  }

  private paramGroup(): FormGroup {
    return this.fb.group({
      name: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_]{1,40}$/)]],
      location: ['BODY', Validators.required],
      type: ['string', Validators.required],
      required: [true],
      description: ['']
    });
  }
}
