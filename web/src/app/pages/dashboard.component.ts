import { Component, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { FilterBarComponent } from '../core/filter-bar.component';
import { FilterStateService } from '../core/filter-state.service';
import { MetricWithContext, PersonaScope } from '../core/models';

/**
 * The metric board. Every card carries the value AND its context — target, prior period,
 * sample size, and the largest contributor — because the whole product premise is that a
 * bare number is not an answer.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [FormsModule, DecimalPipe, FilterBarComponent],
  template: `
    <h1 class="page-title">Dashboard</h1>
    <p class="page-sub">Every metric with its context — target, trend, and who is driving it.</p>

    <app-filter-bar [showPersona]="true" (apply)="load()">
      <label class="inline-check">
        <input type="checkbox" [(ngModel)]="scopeToPersona" (ngModelChange)="load()" />
        Only this persona's metrics
      </label>
    </app-filter-bar>

    @if (scopeToPersona) {
      <p class="page-sub">
        Showing the {{ personaLabel() }} lens — the metrics that persona owns
        ({{ personaMetrics().join(', ') || 'loading…' }}).
      </p>
    }

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
  protected fs = inject(FilterStateService);

  /** Off by default: the board shows everything until you ask for one persona's slice. */
  scopeToPersona = false;

  metrics = signal<MetricWithContext[]>([]);
  personaMetrics = signal<string[]>([]);
  loading = signal(false);
  error = signal('');

  constructor() {
    this.fs.loadOptions();
    this.load();
  }

  personaLabel(): string {
    return this.fs.personas().find((p) => p.id === this.fs.persona())?.displayName
        ?? this.fs.persona();
  }

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

    // Both calls are needed before the board can be filtered, so join them rather than
    // letting the metric response race the persona scope and render an unfiltered board.
    // The persona's metric list comes from the server, so persona scoping stays a config
    // change on the backend rather than a duplicated list in the UI.
    forkJoin({
      metrics: this.api.metrics(this.fs.from(), this.fs.to(), this.fs.buParam()),
      scopes: this.api.personaScopes().pipe(catchError(() => of([] as PersonaScope[]))),
    }).subscribe({
      next: ({ metrics, scopes }) => {
        const owned = scopes.find((p) => p.id === this.fs.persona())?.metrics ?? [];
        this.personaMetrics.set(owned);
        this.metrics.set(
          this.scopeToPersona && owned.length
            ? metrics.filter((m) => owned.includes(m.metric))
            : metrics);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(`Could not reach the service (${e?.status ?? '?'}). Is it on :8080?`);
        this.loading.set(false);
      },
    });
  }
}
