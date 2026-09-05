import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { FilterStateService } from '../core/filter-state.service';
import { AlertDefinition, InAppAlert } from '../core/models';

/**
 * The alert inbox.
 *
 * Alerts are delivered into the app rather than to a mailbox, so the whole
 * sense → reason → notify loop is visible with nothing external configured. The trigger
 * buttons call the same endpoint the 07:00 scheduler calls, so what you see here is the
 * real path, not a demo shortcut.
 */
@Component({
  selector: 'app-alerts',
  standalone: true,
  imports: [FormsModule, DatePipe],
  template: `
    <h1 class="page-title">Alerts</h1>
    <p class="page-sub">
      Delivered into the app. Every one of these was produced by the same generator the
      scheduler runs — the buttons below just run it now instead of at 07:00.
    </p>

    <div class="controls">
      <label>Business unit
        <select [ngModel]="fs.businessUnit()" (ngModelChange)="fs.businessUnit.set($event)">
          <option value="">All business units</option>
          @for (b of fs.businessUnits(); track b) { <option [value]="b">{{ b }}</option> }
        </select>
      </label>
      <label class="inline-check">
        <input type="checkbox" [(ngModel)]="unreadOnly" (ngModelChange)="load()" />
        unread only
      </label>
      <button class="primary" (click)="load()">Refresh</button>
      @if (summary() > 0) {
        <button (click)="readAll()">Mark all read ({{ summary() }})</button>
      }
    </div>

    <div class="card" style="margin-bottom:18px">
      <h3 style="margin-top:0">Run an alert now</h3>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
        @for (d of definitions(); track d.code) {
          <button (click)="trigger(d.code)" [disabled]="running() === d.code">
            {{ running() === d.code ? 'Running…' : d.name }}
          </button>
        }
        @if (!definitions().length) { <span class="muted">Loading…</span> }
      </div>
      @if (lastRun()) { <p class="muted" style="margin:10px 0 0">{{ lastRun() }}</p> }
    </div>

    @if (error()) { <div class="card error">{{ error() }}</div> }
    @if (loading()) { <div class="card muted">Loading…</div> }
    @if (!loading() && !alerts().length) {
      <div class="card muted">
        No alerts yet. Run one above — a quiet window is itself a finding, so a report is
        still produced and delivered.
      </div>
    }

    @for (a of alerts(); track a.id) {
      <div class="card alert-row" [class.unread]="!a.read_at">
        <div class="alert-head">
          <span class="badge"
                [class.b-bad]="a.severity === 'CRITICAL'"
                [class.b-warn]="a.severity === 'WARNING'"
                [class.b-ok]="a.severity === 'INFO'">{{ a.severity }}</span>
          <strong>{{ a.title }}</strong>
          <span class="spacer"></span>
          <span class="muted small">
            {{ a.persona_code }}@if (a.business_unit) { · {{ a.business_unit }} }
            · {{ a.created_at | date: 'd MMM, HH:mm' }}
          </span>
        </div>
        <div class="alert-actions">
          <button (click)="toggle(a)">{{ open() === a.id ? 'Hide' : 'Open' }}</button>
          @if (!a.read_at) { <button (click)="markRead(a)">Mark read</button> }
          <button (click)="dismiss(a)">Dismiss</button>
        </div>
        @if (open() === a.id && a.body) {
          <div class="alert-body" [innerHTML]="a.body"></div>
        }
      </div>
    }
  `,
  styles: [`
    .alert-row { margin-bottom: 12px; }
    .alert-row.unread { border-left: 3px solid var(--accent, #1F5FA8); }
    .alert-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .spacer { flex: 1; }
    .small { font-size: 12px; }
    .alert-actions { display: flex; gap: 8px; margin-top: 10px; }
    .alert-body {
      margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--border, #ddd);
      overflow-x: auto; font-size: 13px;
    }
    .alert-body table { border-collapse: collapse; width: 100%; margin: 10px 0; }
    .alert-body th, .alert-body td {
      text-align: left; padding: 5px 10px 5px 0;
      border-bottom: 1px solid var(--border, #eee);
    }
  `],
})
export class AlertsComponent {
  private api = inject(ApiService);
  protected fs = inject(FilterStateService);

  alerts = signal<InAppAlert[]>([]);
  definitions = signal<AlertDefinition[]>([]);
  summary = signal(0);
  open = signal<number | null>(null);
  running = signal<string | null>(null);
  lastRun = signal('');
  loading = signal(false);
  error = signal('');
  unreadOnly = false;

  constructor() {
    this.fs.loadOptions();
    this.api.alertDefinitions().subscribe({
      next: (d) => this.definitions.set(d),
      error: () => this.definitions.set([]),
    });
    this.load();
  }

  load(): void {
    this.loading.set(true); this.error.set('');
    this.api.alertInbox(undefined, this.fs.buParam(), this.unreadOnly).subscribe({
      next: (a) => { this.alerts.set(a); this.loading.set(false); },
      error: (e) => {
        this.error.set(`Could not load alerts (${e?.status ?? '?'}).`);
        this.loading.set(false);
      },
    });
    this.api.alertSummary().subscribe({
      next: (s) => this.summary.set(s.unread),
      error: () => this.summary.set(0),
    });
  }

  trigger(code: string): void {
    this.running.set(code);
    this.lastRun.set('');
    this.api.triggerAlert(code, this.fs.to(), this.fs.buParam()).subscribe({
      next: (o) => {
        // SUPPRESSED is a real outcome, not a failure — say which it was.
        this.lastRun.set(`${code}: ${o.status}${o.headline ? ' — ' + o.headline : ''}`);
        this.running.set(null);
        this.load();
      },
      error: (e) => {
        this.lastRun.set(`${code} failed (${e?.status ?? '?'})`);
        this.running.set(null);
      },
    });
  }

  toggle(a: InAppAlert): void {
    this.open.set(this.open() === a.id ? null : a.id);
    if (!a.read_at) this.markRead(a);
  }

  markRead(a: InAppAlert): void {
    this.api.markAlertRead(a.id).subscribe({ next: () => this.load() });
  }

  readAll(): void {
    this.api.markAllAlertsRead().subscribe({ next: () => this.load() });
  }

  dismiss(a: InAppAlert): void {
    this.api.dismissAlert(a.id).subscribe({ next: () => this.load() });
  }
}
