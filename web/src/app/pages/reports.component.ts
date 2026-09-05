import { Component, inject, signal } from '@angular/core';
import { DecimalPipe, SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import {
  AlertDefinition, DeliveryRow, ReportFactRow, ReportHistoryRow, ReportOutcome, ReportPreview,
} from '../core/models';

/**
 * Reports — run an alert on demand, preview it before anything is stored or sent, and
 * browse everything that has already gone out with the exact facts each one was written
 * from. "Preview" and "Run" hit two different endpoints on purpose: preview never touches
 * the database, so you can iterate on wording risk-free before a report becomes history.
 */
@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [FormsModule, DecimalPipe, SlicePipe],
  template: `
    <h1 class="page-title">Reports</h1>
    <p class="page-sub">Every scheduled alert, runnable on demand — plus the full history of what went out.</p>

    <div class="card">
      <h3>Alert definitions</h3>
      @if (error()) { <div class="card error">{{ error() }}</div> }
      <table>
        <tr><th>code</th><th>name</th><th>persona</th><th class="num">lookback</th>
          <th class="num">compare</th><th>only if actionable</th><th>implemented</th><th></th></tr>
        @for (a of alerts(); track a.code) {
          <tr>
            <td>{{ a.code }}</td><td>{{ a.name }}</td><td class="muted">{{ a.persona_name }}</td>
            <td class="num">{{ a.lookback_days }}d</td><td class="num">{{ a.compare_days }}d</td>
            <td>{{ a.send_only_if_actionable ? 'yes' : 'no' }}</td>
            <td><span class="badge" [class.b-ok]="a.implemented" [class.b-bad]="!a.implemented">
              {{ a.implemented ? 'yes' : 'missing generator' }}</span></td>
            <td style="display:flex;gap:6px">
              <button [disabled]="!a.implemented || busy()" (click)="preview(a.code)">Preview</button>
              <button class="primary" [disabled]="!a.implemented || busy()"
                      (click)="run(a.code)">Run</button>
            </td>
          </tr>
        }
      </table>
    </div>

    @if (outcome(); as o) {
      <div class="card">
        <h3>Run result <span class="badge" [class.b-ok]="o.status==='SENT'"
              [class.b-warn]="o.status==='GENERATED'" [class.b-bad]="o.status==='SUPPRESSED'">
              {{ o.status }}</span></h3>
        <p>{{ o.headline }}</p>
        <div class="muted">severity {{ o.severity | number:'1.2-2' }} · actionable {{ o.actionable }}
          · report #{{ o.reportId }}</div>
      </div>
    }

    @if (preview_(); as p) {
      <div class="card">
        <h3>Preview <span class="badge b-info">{{ p.generatedBy }}, not stored</span></h3>
        <p style="white-space:pre-wrap">{{ p.body }}</p>
        <div class="muted">{{ p.recommendedAction }}</div>
      </div>
    }

    <div class="card">
      <h3>History</h3>
      <div class="controls">
        <label>Persona <input [(ngModel)]="persona" placeholder="(all)" /></label>
        <label>Business unit <input [(ngModel)]="bu" placeholder="(all)" /></label>
        <button (click)="loadHistory()">Filter</button>
      </div>
      <table>
        <tr><th>when</th><th>alert</th><th>persona</th><th>headline</th>
          <th class="num">severity</th><th>status</th><th></th></tr>
        @for (r of history(); track r.id) {
          <tr>
            <td class="muted">{{ r.created_at | slice:0:16 }}</td>
            <td>{{ r.alert_code ?? '—' }}</td><td>{{ r.persona_name }}</td>
            <td>{{ r.headline }}</td>
            <td class="num">{{ r.severity_score | number:'1.2-2' }}</td>
            <td><span class="badge" [class.b-ok]="r.status==='SENT'"
                  [class.b-warn]="r.status==='GENERATED'" [class.b-bad]="r.status==='SUPPRESSED'">
              {{ r.status }}</span></td>
            <td><button (click)="inspect(r.id)">Facts &amp; deliveries</button></td>
          </tr>
        }
      </table>
    </div>

    @if (selectedId() !== null) {
      <div class="card">
        <h3>Report #{{ selectedId() }}</h3>
        <h4>Facts</h4>
        <table>
          <tr><th>metric</th><th>slice</th><th class="num">value</th><th>reference</th>
            <th>verdict</th><th class="num">contribution</th></tr>
          @for (f of facts(); track f.metric_id + (f.dimension_value ?? '')) {
            <tr>
              <td>{{ f.metric_id }}</td>
              <td class="muted">{{ f.dimension_value ?? '—' }}</td>
              <td class="num">{{ f.value }} {{ f.unit }}</td>
              <td class="muted">{{ f.reference_kind }}: {{ f.reference_value }}</td>
              <td>{{ f.verdict }}</td>
              <td class="num">{{ f.contribution ?? '—' }}</td>
            </tr>
          }
        </table>
        <h4>Deliveries</h4>
        @if (deliveries().length === 0) {
          <div class="muted">No delivery attempted (report was suppressed or preview-only).</div>
        } @else {
          <table>
            <tr><th>channel</th><th>target</th><th>status</th><th>error</th><th>sent</th></tr>
            @for (d of deliveries(); track d.target + d.sent_at) {
              <tr><td>{{ d.channel_kind }}</td><td>{{ d.target }}</td>
                  <td>{{ d.status }}</td><td class="muted">{{ d.error ?? '—' }}</td>
                  <td class="muted">{{ d.sent_at | slice:0:16 }}</td></tr>
            }
          </table>
        }
      </div>
    }
  `,
})
export class ReportsComponent {
  private api = inject(ApiService);
  persona = '';
  bu = '';

  alerts = signal<AlertDefinition[]>([]);
  history = signal<ReportHistoryRow[]>([]);
  facts = signal<ReportFactRow[]>([]);
  deliveries = signal<DeliveryRow[]>([]);
  outcome = signal<ReportOutcome | null>(null);
  preview_ = signal<ReportPreview | null>(null);
  selectedId = signal<number | null>(null);
  busy = signal(false);
  error = signal('');

  constructor() { this.loadAlerts(); this.loadHistory(); }

  loadAlerts(): void {
    this.api.alertDefinitions().subscribe({
      next: (a) => this.alerts.set(a),
      error: (e) => this.error.set(`Could not load alerts (${e?.status ?? '?'}).`),
    });
  }

  loadHistory(): void {
    this.api.reports(this.persona || undefined, this.bu || undefined).subscribe({
      next: (r) => this.history.set(r),
      error: (e) => this.error.set(`Could not load history (${e?.status ?? '?'}).`),
    });
  }

  run(code: string): void {
    this.busy.set(true); this.outcome.set(null);
    this.api.runAlert(code, undefined, this.bu || undefined).subscribe({
      next: (o) => { this.outcome.set(o); this.busy.set(false); this.loadHistory(); },
      error: (e) => { this.error.set(`Run failed (${e?.status ?? '?'}).`); this.busy.set(false); },
    });
  }

  preview(code: string): void {
    this.busy.set(true); this.preview_.set(null);
    this.api.previewAlert(code, undefined, this.bu || undefined).subscribe({
      next: (p) => { this.preview_.set(p); this.busy.set(false); },
      error: (e) => { this.error.set(`Preview failed (${e?.status ?? '?'}).`); this.busy.set(false); },
    });
  }

  inspect(id: number): void {
    this.selectedId.set(id);
    this.api.reportFacts(id).subscribe({ next: (f) => this.facts.set(f) });
    this.api.deliveries(id).subscribe({ next: (d) => this.deliveries.set(d) });
  }
}
