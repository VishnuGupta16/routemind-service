import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { FilterBarComponent } from '../core/filter-bar.component';
import { FilterStateService } from '../core/filter-state.service';
import { Signal } from '../core/models';

/**
 * Operational signals — the Transport Manager's page.
 *
 * The Facilities Head reads a monthly report; the Manager needs "what's wrong right now
 * and who do I chase." So this page skips the narrative and goes straight to a ranked list
 * of every metric that is degrading, each with WHY (shape: sudden vs incremental) and
 * WHERE (the worst-hit slice), sourced from MetricDegradationService via the same
 * deterministic trend classifier the chatbot uses — no separate logic to keep in sync.
 */
@Component({
  selector: 'app-signals',
  standalone: true,
  imports: [FormsModule, DecimalPipe, FilterBarComponent],
  template: `
    <h1 class="page-title">Operational signals</h1>
    <p class="page-sub">
      Every metric, ranked by urgency. Shape tells you whether to act today (SUDDEN) or plan
      a fix (INCREMENTAL).
    </p>

    <app-filter-bar (apply)="load()">
      <label class="inline-check">
        <input type="checkbox" [(ngModel)]="onlyDegrading" (change)="load()" />
        only degrading
      </label>
    </app-filter-bar>

    @if (error()) { <div class="card error">{{ error() }}</div> }
    @if (loading()) { <div class="card muted">Scanning…</div> }

    @if (!loading() && sorted().length === 0 && !error()) {
      <div class="card muted">Nothing degrading in this window.</div>
    }

    @for (s of sorted(); track s.metricId) {
      <div class="card">
        <div style="display:flex;justify-content:space-between;align-items:start;gap:12px">
          <div>
            <div style="font-weight:700">{{ s.displayName }}</div>
            <div class="muted">
              latest {{ format(s, s.latest) }} · target {{ format(s, s.target) }}
              @if (s.changePerBucket !== null) {
                · {{ s.changePerBucket > 0 ? '+' : '' }}{{ s.changePerBucket | number:'1.2-2' }}
                {{ s.unit }} / bucket
              }
            </div>
          </div>
          <div style="display:flex;gap:6px;flex-shrink:0">
            <span class="badge" [class.b-bad]="s.shape==='SUDDEN'"
                  [class.b-warn]="s.shape==='INCREMENTAL'"
                  [class.b-ok]="s.shape==='STABLE' || s.shape==='IMPROVING'">
              {{ s.shape }}
            </span>
            <span class="badge" [class.b-ok]="s.status==='OK'"
                  [class.b-warn]="s.status==='AT_RISK'" [class.b-bad]="s.status==='BREACH'">
              {{ s.status }}
            </span>
          </div>
        </div>

        <p style="margin:8px 0 4px">{{ s.reason }}</p>

        @if (s.worstSlice) {
          <div class="muted">
            worst {{ s.worstSliceDimension }}: <strong>{{ s.worstSlice }}</strong>
          </div>
        }

        <details style="margin-top:8px">
          <summary class="muted">Bucketed history ({{ s.series.length }})</summary>
          <table>
            <tr><th>from</th><th>to</th><th class="num">value</th><th class="num">n</th></tr>
            @for (b of s.series; track b.from) {
              <tr><td>{{ b.from }}</td><td>{{ b.to }}</td>
                  <td class="num">{{ b.value | number:'1.1-2' }}</td>
                  <td class="num">{{ b.sampleSize | number }}</td></tr>
            }
          </table>
        </details>
      </div>
    }
  `,
})
export class SignalsComponent {
  private api = inject(ApiService);
  protected fs = inject(FilterStateService);
  onlyDegrading = true;
  signals = signal<Signal[]>([]);
  loading = signal(false);
  error = signal('');

  sorted = computed(() => [...this.signals()].sort((a, b) => b.urgency - a.urgency));

  constructor() { this.fs.loadOptions(); this.load(); }

  format(s: Signal, v: number): string {
    switch (s.unit) {
      case 'percent': return `${v.toFixed(1)}%`;
      case 'currency': return `₹${Math.round(v).toLocaleString()}`;
      case 'rating': return v.toFixed(2);
      default: return v.toLocaleString();
    }
  }

  load(): void {
    this.loading.set(true); this.error.set('');
    const call = this.onlyDegrading
      ? this.api.degrading(this.fs.from(), this.fs.to(), this.fs.buParam())
      : this.api.signals(this.fs.from(), this.fs.to(), this.fs.buParam());
    call.subscribe({
      next: (s) => { this.signals.set(s); this.loading.set(false); },
      error: (e) => {
        this.error.set(`Could not reach the service (${e?.status ?? '?'}). Is it running on :8080?`);
        this.loading.set(false);
      },
    });
  }
}
