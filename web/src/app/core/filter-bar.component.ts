import { Component, Input, Output, EventEmitter, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FilterStateService } from './filter-state.service';

/**
 * The shared filter bar: period, persona and business unit.
 *
 * Both lists are data-driven — business units come from the loaded dataset and personas
 * from the persona registry — so typing an exact string like "vanta-Aus" is no longer a
 * prerequisite for filtering, which was the previous behaviour.
 *
 * `showPersona` is off by default because not every page is persona-scoped: the metric
 * board and the OTA decomposition are the same numbers whoever is asking.
 */
@Component({
  selector: 'app-filter-bar',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="controls">
      <label>From
        <input type="date" [ngModel]="fs.from()" (ngModelChange)="fs.from.set($event)" />
      </label>
      <label>To
        <input type="date" [ngModel]="fs.to()" (ngModelChange)="fs.to.set($event)" />
      </label>

      @if (showPersona) {
        <label>Persona
          <select [ngModel]="fs.persona()" (ngModelChange)="fs.persona.set($event)">
            @for (p of fs.personas(); track p.id) {
              <option [value]="p.id">{{ p.displayName }}</option>
            }
          </select>
        </label>
      }

      <label>Business unit
        <select [ngModel]="fs.businessUnit()" (ngModelChange)="fs.businessUnit.set($event)">
          <option value="">All business units</option>
          @for (b of fs.businessUnits(); track b) {
            <option [value]="b">{{ b }}</option>
          }
        </select>
      </label>

      <button class="primary" (click)="apply.emit()">Refresh</button>
      <ng-content />
    </div>
  `,
})
export class FilterBarComponent {
  @Input() showPersona = false;
  @Output() apply = new EventEmitter<void>();

  protected fs = inject(FilterStateService);

  constructor() { this.fs.loadOptions(); }
}
