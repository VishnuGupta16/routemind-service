import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';

/**
 * The filters shared by every page: period, persona and business unit.
 *
 * Held centrally so switching page keeps the lens you had chosen — moving from the
 * dashboard to "Why is OTA down?" should not silently reset you to all-units.
 *
 * The option lists are loaded once from the service (business units come from the data
 * that is actually loaded, personas from the persona registry), so neither is hard-coded
 * in the UI and a new tenant needs no front-end change.
 */
@Injectable({ providedIn: 'root' })
export class FilterStateService {
  private api = inject(ApiService);

  /** '' means "all business units" — the API treats a missing param as no filter. */
  readonly businessUnit = signal<string>('');
  readonly persona = signal<string>('TRANSPORT_MANAGER');
  readonly from = signal<string>('2026-07-01');
  readonly to = signal<string>('2026-07-31');

  readonly businessUnits = signal<string[]>([]);
  readonly personas = signal<{ id: string; displayName: string }[]>([]);

  private loaded = false;

  /** Idempotent — every page calls it, only the first does the work. */
  loadOptions(): void {
    if (this.loaded) return;
    this.loaded = true;

    this.api.health().subscribe({
      next: (h) => {
        this.businessUnits.set(h.businessUnits ?? []);
        // Default the period to the range the loaded data actually covers, so a fresh
        // dataset does not open on an empty screen.
        if (h.dateRange?.length === 2) {
          this.from.set(h.dateRange[0]);
          this.to.set(h.dateRange[1]);
        }
      },
      error: () => this.businessUnits.set([]),
    });

    this.api.personaScopes().subscribe({
      next: (ps) => this.personas.set(
        ps.map((p) => ({ id: p.id, displayName: p.displayName }))),
      // Fall back to the three known personas so the selector still works offline.
      error: () => this.personas.set([
        { id: 'TRANSPORT_MANAGER', displayName: 'Transport Manager' },
        { id: 'FACILITIES_HEAD', displayName: 'Transport & Facilities Head' },
        { id: 'LINE_MANAGER', displayName: 'Line Manager' },
      ]),
    });
  }

  /** undefined rather than '' — ApiService drops undefined params entirely. */
  buParam(): string | undefined {
    return this.businessUnit() || undefined;
  }
}
