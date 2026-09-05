import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { FilterBarComponent } from '../core/filter-bar.component';
import { FilterStateService } from '../core/filter-state.service';
import { DualAnswer, Driver } from '../core/models';

/**
 * "Why is OTA down?" — the dual-track answer.
 *
 * Left: the deterministic decomposition (arithmetic on the trip table). Right: the AI
 * narrative over those exact numbers. Shown together on purpose — the rules prove the AI is
 * not inventing, the AI makes the rules readable.
 */
@Component({
  selector: 'app-why-ota',
  standalone: true,
  imports: [FormsModule, DecimalPipe, FilterBarComponent],
  template: `
    <h1 class="page-title">Why is OTA down?</h1>
    <p class="page-sub">Answered two ways so you can see the AI is not inventing anything.</p>

    <app-filter-bar (apply)="run()" />

    @if (error()) { <div class="card error">{{ error() }}</div> }
    @if (loading()) { <div class="card muted">Analysing…</div> }

    @if (data(); as d) {
      <div class="grid" style="grid-template-columns:repeat(4,1fr)">
        <div class="card"><div class="muted">OTA now</div>
          <div class="metric-value">{{ d.facts.otaNow }}%</div>
          <div class="metric-ctx" [class.down]="d.facts.otaChange<0" [class.up]="d.facts.otaChange>=0">
            {{ d.facts.otaChange > 0 ? '+' : '' }}{{ d.facts.otaChange }} pts
          </div>
        </div>
        <div class="card"><div class="muted">OTA prior</div>
          <div class="metric-value">{{ d.facts.otaPrev }}%</div></div>
        <div class="card"><div class="muted">Trips (now)</div>
          <div class="metric-value">{{ d.facts.tripsNow | number }}</div></div>
        <div class="card"><div class="muted">Verdict</div>
          <div class="metric-value" style="font-size:18px">
            <span class="badge" [class.b-bad]="d.facts.declined" [class.b-ok]="!d.facts.declined">
              {{ d.facts.declined ? 'DECLINED' : 'held/up' }}
            </span>
          </div></div>
      </div>

      <div class="two-col">
        <div class="card">
          <h3>Rule-based <span class="badge b-ok">deterministic</span></h3>
          <p>{{ d.ruleBased.explanation }}</p>
          <div class="muted">{{ d.ruleBased.note }}</div>
        </div>
        <div class="card">
          <h3>AI
            <span class="badge" [class.b-info]="d.ai.source==='LLM'"
                  [class.b-warn]="d.ai.source!=='LLM'">
              {{ d.ai.source === 'LLM' ? 'model' : 'no key — mirrors rules' }}
            </span>
          </h3>
          <p>{{ d.ai.explanation }}</p>
          <div class="muted">{{ d.ai.note }}</div>
        </div>
      </div>

      <div class="card">
        <h3>The decomposition</h3>
        <p class="muted">Negative contribution = pulled OTA down. Contributions sum to the total change.</p>
        @for (dim of dims; track dim.key) {
          @if (rows(d, dim.key).length) {
            <h4 style="margin:12px 0 4px">{{ dim.label }}</h4>
            <table>
              <tr><th>{{ dim.label }}</th><th class="num">contribution (pts)</th>
                <th class="num">OTA prev→now</th><th class="num">trips</th>
                <th class="num">extra late</th></tr>
              @for (r of rows(d, dim.key); track r.value) {
                <tr>
                  <td>{{ r.value }}</td>
                  <td class="num" [class.down]="r.contributionPts<0" [class.up]="r.contributionPts>=0">
                    {{ r.contributionPts }}</td>
                  <td class="num">{{ r.otaPrev }} → {{ r.otaNow }}</td>
                  <td class="num">{{ r.tripsNow | number }}</td>
                  <td class="num">{{ r.lateAdded | number }}</td>
                </tr>
              }
            </table>
          }
        }

        <h4 style="margin:14px 0 4px">Cause mix of late trips</h4>
        <table>
          <tr><th>reason</th><th class="num">share prev</th><th class="num">share now</th>
            <th class="num">change</th><th>controllable</th></tr>
          @for (r of d.facts.reasonMix; track r.reason) {
            <tr><td>{{ r.reason }}</td><td class="num">{{ r.sharePrev }}%</td>
              <td class="num">{{ r.shareNow }}%</td>
              <td class="num" [class.down]="r.changePts>0">{{ r.changePts }}</td>
              <td>{{ r.controllable ? 'DRIVER (A4 unconfirmed)' : '—' }}</td></tr>
          }
        </table>
      </div>
    }
  `,
})
export class WhyOtaComponent {
  private api = inject(ApiService);
  protected fs = inject(FilterStateService);
  data = signal<DualAnswer | null>(null);
  loading = signal(false);
  error = signal('');

  dims = [
    { key: 'byDirection' as const, label: 'Direction' },
    { key: 'byShiftBand' as const, label: 'Shift band' },
    { key: 'byProductType' as const, label: 'Cab type' },
    { key: 'byOffice' as const, label: 'Office' },
    { key: 'byVendor' as const, label: 'Vendor' },
  ];

  constructor() {
    this.fs.loadOptions();
    // Open on June: the month OTA actually fell (97.4% -> 95.4%), so the page lands on a
    // real decomposition rather than on a period where nothing degraded.
    this.fs.from.set('2026-06-01');
    this.fs.to.set('2026-06-30');
    this.run();
  }

  rows(d: DualAnswer, key: (typeof this.dims)[number]['key']): Driver[] {
    return (d.facts[key] ?? []) as Driver[];
  }

  run(): void {
    this.loading.set(true); this.error.set('');
    this.api.otaDual(this.fs.from(), this.fs.to(), this.fs.buParam()).subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: (e) => {
        this.error.set(`Could not reach the service (${e?.status ?? '?'}). Is it running on :8080?`);
        this.loading.set(false);
      },
    });
  }
}
