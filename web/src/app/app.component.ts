import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <nav class="sidebar">
        <div class="brand">RouteMind</div>
        <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
        <a routerLink="/why-ota" routerLinkActive="active">Why is OTA down?</a>
        <a routerLink="/signals" routerLinkActive="active">Operational signals</a>
        <a routerLink="/sla" routerLinkActive="active">SLA &amp; vendors</a>
        <a routerLink="/reports" routerLinkActive="active">Reports</a>
        <a routerLink="/admin" routerLinkActive="active">Admin</a>
        <a routerLink="/ask" routerLinkActive="active">Ask (chatbot)</a>
      </nav>
      <main class="main">
        <router-outlet />
      </main>
    </div>
  `,
})
export class AppComponent {}
