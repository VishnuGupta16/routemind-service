import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ChatAnswer } from '../core/models';

interface Turn { role: 'user' | 'bot'; text: string; answer?: ChatAnswer; }

/**
 * The QA chatbot panel.
 *
 * The user types a question; the Java service classifies the persona, runs the real
 * diagnosis and formats the reply. This component just renders the turn — and, importantly,
 * shows the persona it inferred and the FACTS behind the answer, so the reply can be trusted
 * rather than taken on faith. The chatbot never invents a number, and this surface makes
 * that checkable.
 */
@Component({
  selector: 'app-chatbot',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1 class="page-title">Ask RouteMind</h1>
    <p class="page-sub">
      Ask in plain language. The system infers who is asking, finds the bad metrics and the
      reason behind each, and answers in that persona's voice — every number shown with its
      source so you can check it.
    </p>

    <div class="card">
      <div class="muted" style="margin-bottom:8px">Try:</div>
      @for (ex of examples; track ex) {
        <button style="margin:0 6px 6px 0" (click)="send(ex)">{{ ex }}</button>
      }
    </div>

    @for (t of turns(); track $index) {
      @if (t.role === 'user') {
        <div class="card" style="background:var(--panel-2)">
          <strong>You</strong><div>{{ t.text }}</div>
        </div>
      } @else {
        <div class="card">
          @if (t.answer) {
            <div style="margin-bottom:8px">
              <span class="badge b-info">{{ t.answer.persona }}</span>
              <span class="badge" [class.b-ok]="t.answer.answerSource==='LLM'"
                    [class.b-warn]="t.answer.answerSource!=='LLM'"
                    style="margin-left:6px">{{ t.answer.answerSource }}</span>
              <span class="muted" style="margin-left:8px">
                {{ t.answer.from }} → {{ t.answer.to }} ·
                persona via {{ t.answer.personaSource }} ({{ t.answer.personaRationale }})
              </span>
            </div>
            @for (s of t.answer.structured; track s.title) {
              @if (s.lines.length) {
                <div style="margin-bottom:8px">
                  <div class="muted" style="font-weight:700;letter-spacing:.04em">{{ s.title }}</div>
                  @for (line of s.lines; track line) { <div>• {{ line }}</div> }
                </div>
              }
            }
            <details>
              <summary class="muted">Facts behind this answer ({{ t.answer.facts.length }})</summary>
              <table>
                <tr><th>metric</th><th>slice</th><th>value</th><th>reference</th><th>verdict</th></tr>
                @for (f of t.answer.facts; track $index) {
                  <tr><td>{{ f.metric }}</td><td>{{ f.slice }}</td>
                      <td>{{ f.value }}</td><td>{{ f.reference }}</td><td>{{ f.verdict }}</td></tr>
                }
              </table>
            </details>
          } @else {
            <div>{{ t.text }}</div>
          }
        </div>
      }
    }

    <div class="card" style="position:sticky;bottom:12px">
      <div style="display:flex;gap:8px">
        <input style="flex:1" [(ngModel)]="draft" (keyup.enter)="send(draft)"
               placeholder="e.g. Why is OTA down and which vendor should I chase?" />
        <button class="primary" (click)="send(draft)" [disabled]="busy()">
          {{ busy() ? '…' : 'Ask' }}
        </button>
      </div>
    </div>
  `,
})
export class ChatbotComponent {
  private api = inject(ApiService);
  turns = signal<Turn[]>([]);
  busy = signal(false);
  draft = '';

  examples = [
    'Why is OTA down last month?',
    'Are we over budget and should we renegotiate any vendor?',
    'What is degrading right now and who do I chase?',
  ];

  send(q: string): void {
    const question = (q ?? '').trim();
    if (!question || this.busy()) return;
    this.draft = '';
    this.turns.update((t) => [...t, { role: 'user', text: question }]);
    this.busy.set(true);
    this.api.chat(question).subscribe({
      next: (a) => {
        this.turns.update((t) => [...t, { role: 'bot', text: a.answer, answer: a }]);
        this.busy.set(false);
      },
      error: (e) => {
        this.turns.update((t) => [...t, {
          role: 'bot',
          text: `Could not reach the service (${e?.status ?? '?'}). Is routemind-service running on :8080?`,
        }]);
        this.busy.set(false);
      },
    });
  }
}
