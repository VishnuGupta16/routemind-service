/** Types mirroring the Java DTOs. Only the fields the UI reads are declared. */

export interface MetricWithContext {
  metric: string;
  displayName: string;
  unit: string;
  direction: string;
  sampleSize: number;
  value: number;
  target: number;
  priorValue: number | null;
  vsTarget: number | null;
  vsPrior: number | null;
  status: 'OK' | 'AT_RISK' | 'BREACH';
  attributionDimension: string;
  topContributors: { member: string; count: number; pct: number }[];
  headline: string;
}

export interface Driver {
  dimension: string;
  value: string;
  otaNow: number;
  otaPrev: number;
  otaChange: number;
  tripsNow: number;
  lateNow: number;
  lateAdded: number;
  contributionPts: number;
}

export interface ReasonShift {
  reason: string;
  sharePrev: number;
  shareNow: number;
  changePts: number;
  controllable: boolean;
}

export interface Diagnosis {
  periodStart: string;
  periodEnd: string;
  priorStart: string;
  priorEnd: string;
  otaNow: number;
  otaPrev: number;
  otaChange: number;
  tripsNow: number;
  tripsPrev: number;
  declined: boolean;
  byDirection: Driver[];
  byShiftBand: Driver[];
  byProductType: Driver[];
  byOffice: Driver[];
  byVendor: Driver[];
  reasonMix: ReasonShift[];
  headlines: string[];
}

export interface Track {
  source: string;
  explanation: string;
  deterministic: boolean;
  note: string;
}

export interface DualAnswer {
  question: string;
  facts: Diagnosis;
  ruleBased: Track;
  ai: Track;
}

export interface Signal {
  metricId: string;
  displayName: string;
  unit: string;
  shape: 'SUDDEN' | 'INCREMENTAL' | 'STABLE' | 'IMPROVING' | 'INSUFFICIENT_DATA';
  status: string;
  latest: number;
  target: number;
  changePerBucket: number | null;
  latestVsRun: number;
  urgency: number;
  worstSlice: string | null;
  worstSliceDimension: string | null;
  reason: string;
  series: { from: string; to: string; value: number; sampleSize: number }[];
}

export interface ComplianceRow {
  vendor: string;
  businessUnit: string;
  productType: string | null;
  shiftType: string | null;
  trips: number;
  lateTrips: number;
  otaPct: number;
  windowMinutes: number;
  target: number;
  tolerancePct: number;
  vsTarget: number;
  status: string;
  slaName: string;
  slaScope: string;
}

export interface VendorFleetRow {
  vendor: string;
  businessUnit: string;
  productType: string;
  shiftType: string;
  trips: number;
  vehicles: number;
  observedOta: number | null;
  verdict: string | null;
  appliedSla: { name: string; scopeLabel?: string } | null;
}

// ---- admin / reports -----------------------------------------------------
// These come back from AdminController's raw SQL queries rather than a Java
// record, so the keys are snake_case exactly as the SELECT wrote them —
// unlike everything above, which is a record and arrives camelCase.

export interface Persona {
  id: number;
  code: string;
  name: string;
  description: string | null;
  decision_rights: string | null;
  has_prompt: boolean;
  prompt_version: number;
  active: boolean;
}

export interface AlertDefinition {
  id: number;
  code: string;
  name: string;
  description: string | null;
  generator_key: string;
  lookback_days: number;
  compare_days: number;
  send_only_if_actionable: boolean;
  active: boolean;
  persona_code: string;
  persona_name: string;
  implemented: boolean;
}

export interface AlertSchedule {
  id: number;
  alert_code: string;
  alert_name: string;
  frequency: string;
  cron_expression: string;
  timezone: string;
  active: boolean;
  last_run_at: string | null;
  last_run_status: string | null;
  last_run_note: string | null;
  next_run_at: string | null;
}

export interface Recipient {
  id: number;
  email: string;
  display_name: string | null;
  business_unit: string | null;
  active: boolean;
}

export interface Subscription {
  id: number;
  email: string;
  display_name: string | null;
  alert_code: string;
  alert_name: string;
  persona_code: string;
  persona_name: string;
  channel_kind: string;
  business_unit: string | null;
  active: boolean;
}

export interface ReportHistoryRow {
  id: number;
  headline: string;
  body: string;
  recommended_action: string | null;
  severity_score: number;
  actionable: boolean;
  status: string;
  generated_by: string;
  business_unit: string | null;
  period_start: string;
  period_end: string;
  created_at: string;
  persona_code: string;
  persona_name: string;
  alert_code: string | null;
}

export interface ReportFactRow {
  metric_id: string;
  dimension: string | null;
  dimension_value: string | null;
  value: number | null;
  unit: string | null;
  sample_size: number | null;
  reference_value: number | null;
  reference_kind: string | null;
  reference_label: string | null;
  delta: number | null;
  direction: string;
  verdict: string;
  contribution: number | null;
  evidence_sql: string | null;
}

export interface DeliveryRow {
  channel_kind: string;
  target: string;
  status: string;
  error: string | null;
  sent_at: string;
}

/** These two ARE Java records (ReportService.Outcome / GeneratedReport), so camelCase. */
export interface ReportOutcome {
  reportId: number;
  alertCode: string;
  status: string;
  headline: string;
  severity: number;
  actionable: boolean;
  delivery: Record<string, number>;
}

export interface ReportPreview {
  alertCode: string;
  personaCode: string;
  businessUnit: string | null;
  periodStart: string;
  periodEnd: string;
  compareStart: string | null;
  compareEnd: string | null;
  headline: string;
  body: string;
  recommendedAction: string | null;
  severityScore: number;
  actionable: boolean;
  generatedBy: string;
  facts: unknown[];
}

/** SlaController hand-builds this map with camelCase keys, so unlike the admin/report
 *  rows above it reads the same way the rest of the app does. */
export interface SlaPolicyRow {
  id: number;
  name: string;
  scope: string;
  terms: string;
  specificity: number;
  businessUnit: string | null;
  vendor: string | null;
  productType: string | null;
  shiftType: string | null;
  otaWindowMinutes: number;
  otaTarget: number;
  tolerancePct: number;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  active: boolean;
}

export interface ChatSection { title: string; lines: string[]; }
export interface ChatFact {
  metric: string; slice: string | null; value: string;
  reference: string; verdict: string;
}
export interface ChatAnswer {
  question: string;
  persona: string;
  personaSource: string;
  personaRationale: string;
  from: string;
  to: string;
  businessUnit: string | null;
  answer: string;
  answerSource: string;
  structured: ChatSection[];
  facts: ChatFact[];
}

/** /api/health/data — also the source of the business-unit list for filters. */
export interface DataHealth {
  status: string;
  dateRange: [string, string];
  tables: { table: string; rows: number }[];
  businessUnits: string[];
}

/** /api/personas — the three personas the product serves, with the metrics each owns. */
export interface PersonaScope {
  id: string;
  displayName: string;
  need: string;
  cadence: string;
  channel: string;
  metrics: string[];
}

/** /api/insights/{persona} — the persona-scoped bundle. */
export interface PersonaBundle {
  persona: string;
  displayName?: string;
  from: string;
  to: string;
  businessUnit: string | null;
  findings: Finding[];
}

export interface Finding {
  metricId: string;
  displayName: string;
  value: number;
  target: number | null;
  priorValue: number | null;
  status: string;
  reason: string | null;
  evidence: string | null;
  narrative: string | null;
  attributionDimension: string | null;
  attribution: { member: string; count: number; pct: number }[] | null;
}

/** /api/alerts — one delivered alert sitting in the in-app inbox. */
export interface InAppAlert {
  id: number;
  report_id: number | null;
  persona_code: string;
  business_unit: string | null;
  title: string;
  body: string | null;
  severity: 'CRITICAL' | 'WARNING' | 'INFO';
  read_at: string | null;
  created_at: string;
}

export interface AlertSummary {
  unread: number;
  bySeverity: { severity: string; n: number }[];
}
