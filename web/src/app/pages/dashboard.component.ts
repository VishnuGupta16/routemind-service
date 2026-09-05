import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { MetricWithContext } from '../core/models';

/**
 * The metric board. Every card carries the value AND its context — target, prior period,
 * sample size, and the largest contributor — because the whole product premise is that a
 * bare number is not an answer.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  template: `
    <h1 class="page-title">Dashboard</h1>
    <p class="page-sub">Every metric with its context — target, trend, and who is driving it.</p>

    <div class="controls">
      <label>From <input type="date" [(ngModel)]="from" /></label>
      <label>To <input type="date" [(ngModel)]="to" /></label>
      <label>Business unit <input [(ngModel)]="bu" placeholder="(all)" /></label>
      <button class="primary" (click)="load()">Refresh</button>
    </div>

    @if (error()) { <div class="card error">{{ error() }}</div> }
    @if (loading()) { <div class="card muted">Loading…</div> }

    <div class="grid">
      @for (m of metrics(); track m.metric) {
        <div class="card">
          <div style="display:flex;justify-content:space-between;align-items:start">
            <div class="muted">{{ m.displayName }}</div>
            <span class="badge" [class.b-ok]="m.status==='OK'"
                  [class.b-warn]="m.status==='AT_RISK'" [class.b-bad]="m.status==='BREACH'">
              {{ m.status }}
            </span>
          </div>
          <div class="metric-value">{{ format(m) }}</div>
          <div class="metric-ctx">
            target {{ format(m, m.target) }}
            @if (m.priorValue !== null) {
              · prior {{ format(m, m.priorValue) }}
              <span [class.down]="worse(m)" [class.up]="!worse(m)">
                ({{ (m.vsPrior ?? 0) > 0 ? '+' : '' }}{{ m.vsPrior }})
              </span>
            }
          </div>
          <div class="metric-ctx">n = {{ m.sampleSize | number }}</div>
          @if (m.topContributors?.length) {
            <div class="metric-ctx">
              largest {{ m.attributionDimension }}: {{ m.topContributors[0].member }}
              ({{ m.topContributors[0].pct }}%)
            </div>
          }
        </div>
      }
    </div>
  `,
})
export class DashboardComponent {
  private api = inject(ApiService);
  from = '2026-07-01';
  to = '2026-07-31';
  bu = '';
  metrics = signal<MetricWithContext[]>([]);
  loading = signal(false);
  error = signal('');

  constructor() { this.load(); }

  worse(m: MetricWithContext): boolean {
    if (m.vsPrior === null) return false;
    return m.direction === 'HIGHER_IS_BETTER' ? m.vsPrior < 0 : m.vsPrior > 0;
  }

  format(m: MetricWithContext, v: number = m.value): string {
    switch (m.unit) {
      case 'percent': return `${v.toFixed(1)}%`;
      case 'currency': return `₹${Math.round(v).toLocaleString()}`;
      case 'rating': return v.toFixed(2);
      default: return v.toLocaleString();
    }
  }

  load(): void {
    this.loading.set(true); this.error.set('');
    this.api.metrics(this.from, this.to, this.bu || undefined).subscribe({
      next: (m) => { this.metrics.set(m); this.loading.set(false); },
      error: (e) => {
        this.error.set(`Could not reach the service (${e?.status ?? '?'}). Is it on :8080?`);
        this.loading.set(false);
      },
    });
  }
}
