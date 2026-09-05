import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AlertDefinition, AlertSchedule, AlertSummary, ChatAnswer, ComplianceRow, DataHealth,
  DeliveryRow, InAppAlert,
  Diagnosis, DualAnswer, MetricWithContext, Persona, PersonaBundle, PersonaScope, Recipient,
  ReportFactRow, ReportHistoryRow, ReportOutcome, ReportPreview, Signal, SlaPolicyRow,
  Subscription, VendorFleetRow,
} from './models';

/**
 * One place that knows the REST surface of the Java service. Components inject this and
 * never build a URL themselves, so an endpoint change is a one-line edit here.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = environment.apiBase;

  private params(obj: Record<string, string | number | null | undefined>): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(obj)) {
      if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
    }
    return p;
  }

  // ---- in-app alert inbox ------------------------------------------------
  /** Alerts delivered into the app rather than to an inbox. */
  alertInbox(persona?: string, bu?: string, unreadOnly = false): Observable<InAppAlert[]> {
    return this.http.get<InAppAlert[]>(`${this.base}/api/alerts`,
      { params: this.params({ persona, businessUnit: bu,
                              unreadOnly: unreadOnly ? 'true' : undefined }) });
  }

  alertSummary(): Observable<AlertSummary> {
    return this.http.get<AlertSummary>(`${this.base}/api/alerts/summary`);
  }

  markAlertRead(id: number): Observable<unknown> {
    return this.http.post(`${this.base}/api/alerts/${id}/read`, null);
  }

  markAllAlertsRead(): Observable<unknown> {
    return this.http.post(`${this.base}/api/alerts/read-all`, null);
  }

  dismissAlert(id: number): Observable<unknown> {
    return this.http.delete(`${this.base}/api/alerts/${id}`);
  }

  /** The "run it now" button. Same path the scheduler takes. */
  triggerAlert(code: string, asOf?: string, bu?: string): Observable<ReportOutcome> {
    return this.http.post<ReportOutcome>(
      `${this.base}/api/alerts/trigger/${code}`, null,
      { params: this.params({ asOf, businessUnit: bu, force: 'true' }) });
  }

  // ---- reference data (drives the filter dropdowns) ----------------------
  /** Business units are discovered from the loaded data, never hard-coded. */
  health(): Observable<DataHealth> {
    return this.http.get<DataHealth>(`${this.base}/api/health/data`);
  }

  /** The three personas the product serves, each with the metrics it owns. */
  personaScopes(): Observable<PersonaScope[]> {
    return this.http.get<PersonaScope[]>(`${this.base}/api/personas`);
  }

  /** Persona-scoped findings — what this persona should be looking at. */
  insights(persona: string, from: string, to: string, bu?: string, limit = 5)
      : Observable<PersonaBundle> {
    return this.http.get<PersonaBundle>(`${this.base}/api/insights/${persona}`,
      { params: this.params({ from, to, businessUnit: bu, limit }) });
  }

  // ---- metrics ----------------------------------------------------------
  metrics(from: string, to: string, bu?: string): Observable<MetricWithContext[]> {
    return this.http.get<MetricWithContext[]>(`${this.base}/api/metrics`,
      { params: this.params({ from, to, businessUnit: bu }) });
  }

  // ---- diagnose ---------------------------------------------------------
  otaDiagnosis(from: string, to: string, bu?: string): Observable<Diagnosis> {
    return this.http.get<Diagnosis>(`${this.base}/api/diagnose/ota`,
      { params: this.params({ from, to, businessUnit: bu }) });
  }
  otaDual(from: string, to: string, bu?: string): Observable<DualAnswer> {
    return this.http.get<DualAnswer>(`${this.base}/api/diagnose/ota/dual`,
      { params: this.params({ from, to, businessUnit: bu }) });
  }
  degrading(from: string, to: string, bu?: string): Observable<Signal[]> {
    return this.http.get<Signal[]>(`${this.base}/api/diagnose/degrading`,
      { params: this.params({ from, to, businessUnit: bu }) });
  }
  signals(from: string, to: string, bu?: string): Observable<Signal[]> {
    return this.http.get<Signal[]>(`${this.base}/api/diagnose/signals`,
      { params: this.params({ from, to, businessUnit: bu }) });
  }

  // ---- SLA --------------------------------------------------------------
  compliance(from: string, to: string, groupBy: string, bu?: string):
      Observable<ComplianceRow[]> {
    return this.http.get<ComplianceRow[]>(`${this.base}/api/sla/compliance`,
      { params: this.params({ from, to, groupBy, businessUnit: bu }) });
  }
  fleet(vendor?: string, bu?: string, on?: string): Observable<VendorFleetRow[]> {
    return this.http.get<VendorFleetRow[]>(`${this.base}/api/sla/fleet`,
      { params: this.params({ vendor, businessUnit: bu, on }) });
  }
  fleetCoverage(on?: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`${this.base}/api/sla/fleet/coverage`,
      { params: this.params({ on }) });
  }
  policies(): Observable<SlaPolicyRow[]> {
    return this.http.get<SlaPolicyRow[]>(`${this.base}/api/sla/policies`);
  }

  // ---- admin / reports --------------------------------------------------
  personas(): Observable<Persona[]> {
    return this.http.get<Persona[]>(`${this.base}/api/admin/personas`);
  }
  updatePersonaPrompt(code: string, promptTemplate: string): Observable<Record<string, unknown>> {
    return this.http.put<Record<string, unknown>>(
      `${this.base}/api/admin/personas/${code}/prompt`, { promptTemplate });
  }
  /** The alert DEFINITIONS (what can be run), not the delivered alerts. */
  alertDefinitions(): Observable<AlertDefinition[]> {
    return this.http.get<AlertDefinition[]>(`${this.base}/api/admin/alerts`);
  }
  channels(): Observable<Record<string, boolean>> {
    return this.http.get<Record<string, boolean>>(`${this.base}/api/admin/channels`);
  }
  schedules(): Observable<AlertSchedule[]> {
    return this.http.get<AlertSchedule[]>(`${this.base}/api/admin/schedules`);
  }
  // A bad cron expression makes the endpoint return 400 with { error, detail, hint }, which
  // Angular routes to the subscriber's error callback (not next) since it isn't 2xx — see
  // AdminSchedule handling in admin.component.ts.
  updateSchedule(id: number, body: {
    frequency?: string; cronExpression: string; timezone?: string; active?: boolean;
  }): Observable<{ id: number; nextRunAt: string }> {
    return this.http.put<{ id: number; nextRunAt: string }>(
      `${this.base}/api/admin/schedules/${id}`, body);
  }
  recipients(): Observable<Recipient[]> {
    return this.http.get<Recipient[]>(`${this.base}/api/admin/recipients`);
  }
  addRecipient(email: string, displayName?: string, businessUnit?: string):
      Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(`${this.base}/api/admin/recipients`,
      { email, displayName: displayName ?? '', businessUnit: businessUnit ?? '' });
  }
  subscriptions(): Observable<Subscription[]> {
    return this.http.get<Subscription[]>(`${this.base}/api/admin/subscriptions`);
  }
  subscribe(body: {
    alertCode: string; email: string; channelKind?: string; personaCode: string; businessUnit?: string;
  }): Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(`${this.base}/api/admin/subscriptions`, body);
  }
  unsubscribe(id: number): Observable<Record<string, unknown>> {
    return this.http.delete<Record<string, unknown>>(`${this.base}/api/admin/subscriptions/${id}`);
  }
  reports(persona?: string, bu?: string, limit = 50): Observable<ReportHistoryRow[]> {
    return this.http.get<ReportHistoryRow[]>(`${this.base}/api/admin/reports`,
      { params: this.params({ persona, businessUnit: bu, limit }) });
  }
  reportFacts(id: number): Observable<ReportFactRow[]> {
    return this.http.get<ReportFactRow[]>(`${this.base}/api/admin/reports/${id}/facts`);
  }
  deliveries(id: number): Observable<DeliveryRow[]> {
    return this.http.get<DeliveryRow[]>(`${this.base}/api/admin/reports/${id}/deliveries`);
  }
  feedback(id: number, body: {
    email?: string; rating: number; aspect?: string; comment?: string;
  }): Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(
      `${this.base}/api/admin/reports/${id}/feedback`, body);
  }
  runAlert(code: string, asOf?: string, bu?: string, force = false): Observable<ReportOutcome> {
    return this.http.post<ReportOutcome>(
      `${this.base}/api/admin/alerts/${code}/run`, null,
      { params: this.params({ asOf, businessUnit: bu, force: String(force) }) });
  }
  previewAlert(code: string, asOf?: string, bu?: string): Observable<ReportPreview> {
    return this.http.get<ReportPreview>(`${this.base}/api/admin/alerts/${code}/preview`,
      { params: this.params({ asOf, businessUnit: bu }) });
  }

  // ---- chatbot ----------------------------------------------------------
  chat(question: string, bu?: string, from?: string, to?: string): Observable<ChatAnswer> {
    return this.http.post<ChatAnswer>(`${this.base}/api/chat`,
      { question, businessUnit: bu ?? null, from: from ?? null, to: to ?? null });
  }
}
