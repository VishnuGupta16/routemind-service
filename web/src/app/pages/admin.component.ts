import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AlertSchedule, Persona, Recipient, Subscription } from '../core/models';

/**
 * Admin — the configuration surface behind every alert: who is a persona, when each alert
 * runs, who receives it, and over which channel. Nothing here is a special admin-only data
 * model — it's the same alert_schedule / recipient / alert_subscription tables the scheduler
 * and notification engine read at 08:00, so a change here takes effect on the next tick with
 * no restart.
 */
@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1 class="page-title">Admin</h1>
    <p class="page-sub">Personas, schedules, recipients and who is subscribed to what.</p>

    @if (error()) { <div class="card error">{{ error() }}</div> }
    @if (notice()) { <div class="card" style="border-color:var(--ok)">{{ notice() }}</div> }

    <div class="card">
      <h3>Personas</h3>
      <table>
        <tr><th>code</th><th>name</th><th>decision rights</th><th>prompt</th><th>active</th></tr>
        @for (p of personas(); track p.code) {
          <tr>
            <td>{{ p.code }}</td><td>{{ p.name }}</td>
            <td class="muted">{{ p.decision_rights ?? '—' }}</td>
            <td>{{ p.has_prompt ? 'v' + p.prompt_version : 'none yet' }}</td>
            <td>{{ p.active ? 'yes' : 'no' }}</td>
          </tr>
        }
      </table>
    </div>

    <div class="card">
      <h3>Channels</h3>
      <div style="display:flex;gap:8px">
        @for (c of channelEntries(); track c[0]) {
          <span class="badge" [class.b-ok]="c[1]" [class.b-bad]="!c[1]">
            {{ c[0] }}: {{ c[1] ? 'available' : 'not configured' }}</span>
        }
      </div>
    </div>

    <div class="card">
      <h3>Schedules</h3>
      <table>
        <tr><th>alert</th><th>frequency</th><th>cron</th><th>timezone</th><th>next run</th>
          <th>last run</th><th>active</th><th></th></tr>
        @for (s of schedules(); track s.id) {
          <tr>
            <td>{{ s.alert_code }}</td>
            <td>
              @if (editing() === s.id) {
                <input [(ngModel)]="draftFrequency" style="width:100px" />
              } @else { {{ s.frequency }} }
            </td>
            <td>
              @if (editing() === s.id) {
                <input [(ngModel)]="draftCron" style="width:140px" />
              } @else { <code>{{ s.cron_expression }}</code> }
            </td>
            <td class="muted">{{ s.timezone }}</td>
            <td class="muted">{{ s.next_run_at ?? '—' }}</td>
            <td class="muted">
              {{ s.last_run_at ?? 'never' }}
              @if (s.last_run_status) {
                <span class="badge" [class.b-ok]="s.last_run_status==='OK'"
                      [class.b-bad]="s.last_run_status!=='OK'">{{ s.last_run_status }}</span>
              }
            </td>
            <td>{{ s.active ? 'yes' : 'no' }}</td>
            <td>
              @if (editing() === s.id) {
                <button class="primary" (click)="saveSchedule(s)">Save</button>
                <button (click)="editing.set(null)">Cancel</button>
              } @else {
                <button (click)="startEdit(s)">Edit cadence</button>
              }
            </td>
          </tr>
        }
      </table>
    </div>

    <div class="card">
      <h3>Recipients</h3>
      <table>
        <tr><th>email</th><th>name</th><th>business unit</th><th>active</th></tr>
        @for (r of recipients(); track r.email) {
          <tr><td>{{ r.email }}</td><td>{{ r.display_name ?? '—' }}</td>
              <td class="muted">{{ r.business_unit ?? '(all)' }}</td>
              <td>{{ r.active ? 'yes' : 'no' }}</td></tr>
        }
      </table>
      <div class="controls" style="margin-top:10px">
        <label>Email <input [(ngModel)]="newEmail" placeholder="name@company.com" /></label>
        <label>Name <input [(ngModel)]="newName" /></label>
        <label>Business unit <input [(ngModel)]="newBu" placeholder="(all)" /></label>
        <button class="primary" (click)="addRecipient()">Add recipient</button>
      </div>
    </div>

    <div class="card">
      <h3>Subscriptions <span class="muted">who receives which alert, as which persona</span></h3>
      <table>
        <tr><th>email</th><th>alert</th><th>persona</th><th>channel</th>
          <th>business unit</th><th>active</th><th></th></tr>
        @for (s of subscriptions(); track s.id) {
          <tr>
            <td>{{ s.email }}</td><td>{{ s.alert_code }}</td><td>{{ s.persona_code }}</td>
            <td>{{ s.channel_kind }}</td><td class="muted">{{ s.business_unit ?? '(all)' }}</td>
            <td>{{ s.active ? 'yes' : 'no' }}</td>
            <td><button (click)="unsubscribe(s.id)">Remove</button></td>
          </tr>
        }
      </table>
      <div class="controls" style="margin-top:10px">
        <label>Email <input [(ngModel)]="subEmail" placeholder="name@company.com" /></label>
        <label>Alert code <input [(ngModel)]="subAlertCode" placeholder="facilities_head_briefing" /></label>
        <label>Persona code <input [(ngModel)]="subPersonaCode" placeholder="FACILITIES_HEAD" /></label>
        <label>Business unit <input [(ngModel)]="subBu" placeholder="(all)" /></label>
        <button class="primary" (click)="subscribe()">Subscribe</button>
      </div>
    </div>
  `,
})
export class AdminComponent {
  private api = inject(ApiService);

  personas = signal<Persona[]>([]);
  channelEntries = signal<[string, boolean][]>([]);
  schedules = signal<AlertSchedule[]>([]);
  recipients = signal<Recipient[]>([]);
  subscriptions = signal<Subscription[]>([]);
  error = signal('');
  notice = signal('');

  editing = signal<number | null>(null);
  draftFrequency = '';
  draftCron = '';

  newEmail = ''; newName = ''; newBu = '';
  subEmail = ''; subAlertCode = ''; subPersonaCode = ''; subBu = '';

  constructor() { this.loadAll(); }

  loadAll(): void {
    this.api.personas().subscribe({
      next: (p) => this.personas.set(p),
      error: (e) => this.error.set(`Could not load personas (${e?.status ?? '?'}).`),
    });
    this.api.channels().subscribe({
      next: (c) => this.channelEntries.set(Object.entries(c)),
    });
    this.api.schedules().subscribe({ next: (s) => this.schedules.set(s) });
    this.api.recipients().subscribe({ next: (r) => this.recipients.set(r) });
    this.api.subscriptions().subscribe({ next: (s) => this.subscriptions.set(s) });
  }

  startEdit(s: AlertSchedule): void {
    this.editing.set(s.id);
    this.draftFrequency = s.frequency;
    this.draftCron = s.cron_expression;
  }

  saveSchedule(s: AlertSchedule): void {
    this.api.updateSchedule(s.id, {
      frequency: this.draftFrequency, cronExpression: this.draftCron,
    }).subscribe({
      next: (res) => {
        this.notice.set(`Schedule ${s.id} updated — next run ${res.nextRunAt}.`);
        this.editing.set(null);
        this.loadAll();
      },
      error: (e) => {
        // 400 body is { error, detail, hint } from AdminController's cron validation.
        const body = e?.error;
        this.error.set(body?.error
          ? `${body.error}: ${body.detail} (${body.hint})`
          : `Update failed (${e?.status ?? '?'}).`);
      },
    });
  }

  addRecipient(): void {
    if (!this.newEmail.trim()) return;
    this.api.addRecipient(this.newEmail.trim(), this.newName || undefined, this.newBu || undefined)
      .subscribe({
        next: () => {
          this.notice.set(`Added ${this.newEmail}.`);
          this.newEmail = ''; this.newName = ''; this.newBu = '';
          this.loadAll();
        },
        error: (e) => this.error.set(`Could not add recipient (${e?.status ?? '?'}).`),
      });
  }

  subscribe(): void {
    if (!this.subEmail.trim() || !this.subAlertCode.trim() || !this.subPersonaCode.trim()) return;
    this.api.subscribe({
      alertCode: this.subAlertCode.trim(), email: this.subEmail.trim(),
      personaCode: this.subPersonaCode.trim(), businessUnit: this.subBu || undefined,
    }).subscribe({
      next: () => {
        this.notice.set(`Subscribed ${this.subEmail} to ${this.subAlertCode}.`);
        this.subEmail = ''; this.subAlertCode = ''; this.subPersonaCode = ''; this.subBu = '';
        this.loadAll();
      },
      error: (e) => this.error.set(`Subscribe failed (${e?.status ?? '?'}). Check the codes match exactly.`),
    });
  }

  unsubscribe(id: number): void {
    this.api.unsubscribe(id).subscribe({ next: () => this.loadAll() });
  }
}
