import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./pages/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'why-ota',
    loadComponent: () =>
      import('./pages/why-ota.component').then((m) => m.WhyOtaComponent),
  },
  {
    path: 'signals',
    loadComponent: () =>
      import('./pages/signals.component').then((m) => m.SignalsComponent),
  },
  {
    path: 'sla',
    loadComponent: () => import('./pages/sla.component').then((m) => m.SlaComponent),
  },
  {
    path: 'reports',
    loadComponent: () =>
      import('./pages/reports.component').then((m) => m.ReportsComponent),
  },
  {
    path: 'admin',
    loadComponent: () => import('./pages/admin.component').then((m) => m.AdminComponent),
  },
  {
    path: 'ask',
    loadComponent: () => import('./pages/chatbot.component').then((m) => m.ChatbotComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
