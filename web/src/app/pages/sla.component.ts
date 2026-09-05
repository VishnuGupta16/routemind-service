import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ComplianceRow, SlaPolicyRow, VendorFleetRow } from '../core/models';

/**
 * SLA & vendors — three views over the same idea: a vendor is only "good" or "bad"
 * relative to the contract it actually signed.
 *
 *  - Scorecard: every vendor (or vendor×cab-type / vendor×shift) judged against its SLA.
 *  - Fleet: every combination actually operating, whether or not it has its own contract yet
 *    (falls back to the group default — that fallback is itself worth seeing).
 *  - Contracts: the raw SLA policy table, most specific first.
 */
@Component({
  selector: 'app-sla',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  template: `
    <h1 class="page-title">SLA &amp; vendors</h1>
    <p class="page-sub">Every vendor judged against the contract it signed — not a flat target.</p>

    <div class="controls">
      <label>From <input type="date" [(ngModel)]="from" /></label>
      <label>To <input type="date" [(ngModel)]="to" /></label>
      <label>Business unit <input [(ngModel)]="bu" placeholder="(all)" /></label>
      <label>Group by
        <select [(ngModel)]="groupBy">
          <option value="vendor">vendor</option>
          <option value="vendor_product">vendor × cab type</option>
          <option value="vendor_shift">vendor × shift type</option>
        </select>
      </label>
      <button class="primary" (click)="loadAll()">Refresh</button>
    </div>

    @if (error()) { <div class="card error">{{ error() }}</div> }

    <div class="card">
      <h3>Scorecard</h3>
      @if (compliance().length === 0) {
        <div class="muted">No vendor cleared the minimum trip count for this window.</div>
      } @else {
        <table>
          <tr><th>vendor</th><th>business unit</th><th>cab type</th><th>shift</th>
            <th class="num">trips</th><th class="num">OTA</th><th class="num">target ±tol</th>
            <th class="num">vs target</th><th>SLA</th><th>status</th></tr>
          @for (r of compliance(); track r.vendor + r.productType + r.shiftType) {
            <tr>
              <td>{{ r.vendor }}</td><td>{{ r.businessUnit }}</td>
              <td>{{ r.productType ?? '—' }}</td><td>{{ r.shiftType ?? '—' }}</td>
              <td class="num">{{ r.trips | number }}</td>
              <td class="num">{{ r.otaPct | number:'1.1-1' }}%</td>
              <td class="num">{{ r.target | number:'1.1-1' }}% ±{{ r.tolerancePct }}</td>
              <td class="num" [class.down]="r.vsTarget<0" [class.up]="r.vsTarget>=0">
                {{ r.vsTarget > 0 ? '+' : '' }}{{ r.vsTarget | number:'1.1-1' }}
              </td>
              <td class="muted">{{ r.slaName }} <span class="muted">({{ r.slaScope }})</span></td>
              <td><span class="badge" [class.b-ok]="r.status==='MET'"
                        [class.b-warn]="r.status==='AT_RISK'" [class.b-bad]="r.status==='BREACH'">
                {{ r.status }}</span></td>
            </tr>
          }
        </table>
      }
    </div>

    <div class="card">
      <h3>Fleet — every combination actually operating</h3>
      @if (fleet().length === 0) {
        <div class="muted">No fleet data loaded yet — try Refresh.</div>
      } @else {
        <table>
          <tr><th>vendor</th><th>business unit</th><th>cab type</th><th>shift</th>
            <th class="num">trips</th><th class="num">vehicles</th>
            <th class="num">observed OTA</th><th>applied SLA</th><th>verdict</th></tr>
          @for (f of fleet(); track f.vendor + f.productType + f.shiftType) {
            <tr>
              <td>{{ f.vendor }}</td><td>{{ f.businessUnit }}</td>
              <td>{{ f.productType }}</td><td>{{ f.shiftType }}</td>
              <td class="num">{{ f.trips | number }}</td>
              <td class="num">{{ f.vehicles | number }}</td>
              <td class="num">{{ f.observedOta !== null ? (f.observedOta | number:'1.1-1') + '%' : '—' }}</td>
              <td class="muted">{{ f.appliedSla?.name ?? 'group default' }}</td>
              <td>
                @if (f.verdict) {
                  <span class="badge" [class.b-ok]="f.verdict==='MET'"
                        [class.b-warn]="f.verdict==='AT_RISK'" [class.b-bad]="f.verdict==='BREACH'">
                    {{ f.verdict }}</span>
                } @else { <span class="muted">too few trips to judge</span> }
              </td>
            </tr>
          }
        </table>
      }
    </div>

    <div class="card">
      <h3>Contracts on file</h3>
      @if (policies().length === 0) {
        <div class="muted">No SLA contracts configured yet — the group default applies everywhere.</div>
      } @else {
        <table>
          <tr><th>name</th><th>scope</th><th>terms</th><th class="num">specificity</th>
            <th>effective</th><th>active</th></tr>
          @for (p of policies(); track p.id) {
            <tr>
              <td>{{ p.name }}</td><td class="muted">{{ p.scope }}</td>
              <td>{{ p.terms }}</td><td class="num">{{ p.specificity }}</td>
              <td class="muted">{{ p.effectiveFrom ?? '—' }} → {{ p.effectiveTo ?? 'open' }}</td>
              <td>{{ p.active ? 'yes' : 'no' }}</td>
            </tr>
          }
        </table>
      }
    </div>
  `,
})
export class SlaComponent {
  private api = inject(ApiService);
  from = '2026-06-01';
  to = '2026-06-30';
  bu = '';
  groupBy = 'vendor';

  compliance = signal<ComplianceRow[]>([]);
  fleet = signal<VendorFleetRow[]>([]);
  policies = signal<SlaPolicyRow[]>([]);
  error = signal('');

  constructor() { this.loadAll(); }

  loadAll(): void {
    this.error.set('');
    this.api.compliance(this.from, this.to, this.groupBy, this.bu || undefined).subscribe({
      next: (r) => this.compliance.set(r),
      error: (e) => this.error.set(`Compliance call failed (${e?.status ?? '?'}).`),
    });
    this.api.fleet(undefined, this.bu || undefined).subscribe({
      next: (r) => this.fleet.set(r),
      error: (e) => this.error.set(`Fleet call failed (${e?.status ?? '?'}).`),
    });
    this.api.policies().subscribe({
      next: (r) => this.policies.set(r),
      error: (e) => this.error.set(`Policies call failed (${e?.status ?? '?'}).`),
    });
  }
}
