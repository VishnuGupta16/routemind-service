-- RouteMind — application schema: personas, alerts, schedules, notifications, reports.
--
-- Deliberately SEPARATE from schema.sql. That file drops and rebuilds the five data tables
-- on every monthly reload; everything here is configuration and history that must survive
-- it. Nothing in this file is ever dropped — it is all CREATE TABLE IF NOT EXISTS.
--
--     schema.sql        the data      (reloaded monthly, disposable)
--     schema_admin.sql  the product   (configuration + history, permanent)
--
-- Run both, in that order:
--     psql "$DSN" -f schema.sql -f schema_admin.sql

-- ================================================================= personas
-- Who a report is FOR. The same underlying numbers become three different reports,
-- because the three personas act on different things — and a report that tries to serve
-- all three serves none of them.
--
-- prompt_template is the Phase 2 hook. Report generation today is deterministic; when an
-- LLM writes the prose it will be given this persona's template plus the structured facts
-- in report_fact, and nothing else. Keeping the prompt in the database rather than in code
-- means the wording can be tuned per persona over time without a deploy — which is the
-- part that actually needs iterating.
CREATE TABLE IF NOT EXISTS persona (
    id            BIGSERIAL PRIMARY KEY,
    code          TEXT NOT NULL UNIQUE,   -- FACILITIES_HEAD | TRANSPORT_MANAGER | LINE_MANAGER
    name          TEXT NOT NULL,
    description   TEXT,
    -- what this persona owns, and therefore what a report may recommend they DO
    decision_rights TEXT,
    -- Phase 2: the system prompt used when an LLM writes this persona's narrative.
    prompt_template TEXT,
    prompt_version  INTEGER NOT NULL DEFAULT 1,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ========================================================== alert definitions
-- An alert is a named, repeatable question asked of the data on a schedule.
--
-- generator_key binds it to a Java component (@Component("facilities_head_briefing")), so
-- adding an alert is a class plus a row — never a change to the scheduler or the notifier.
CREATE TABLE IF NOT EXISTS alert_definition (
    id             BIGSERIAL PRIMARY KEY,
    code           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    description    TEXT,
    persona_id     BIGINT NOT NULL REFERENCES persona(id),
    generator_key  TEXT NOT NULL,          -- which ReportGenerator produces it
    -- Report over the last N days ending at the run date. 1 = yesterday, 7 = last week.
    lookback_days  INTEGER NOT NULL DEFAULT 1,
    -- Compare against the equivalent preceding window, so "down" always means down
    -- against something stated rather than against a feeling.
    compare_days   INTEGER NOT NULL DEFAULT 1,
    -- Suppress the send when nothing crossed a threshold. A briefing that arrives every
    -- day regardless of whether anything happened gets filtered to a folder within a month.
    send_only_if_actionable BOOLEAN NOT NULL DEFAULT TRUE,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ================================================================ schedules
-- When an alert runs. One alert can have several schedules (a daily brief AND a Monday
-- roll-up), which is why this is its own table rather than a column.
--
-- cron_expression is the single source of truth; frequency is a human label for the admin
-- UI so nobody has to read a cron string to know what they configured.
CREATE TABLE IF NOT EXISTS alert_schedule (
    id                  BIGSERIAL PRIMARY KEY,
    alert_definition_id BIGINT NOT NULL REFERENCES alert_definition(id) ON DELETE CASCADE,
    frequency           TEXT NOT NULL,      -- DAILY | MULTI_DAILY | WEEKLY | MONTHLY | CUSTOM
    cron_expression     TEXT NOT NULL,      -- Spring 6-field cron, e.g. '0 0 7 * * MON-FRI'
    timezone            TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at         TIMESTAMPTZ,
    last_run_status     TEXT,               -- OK | NO_DATA | SUPPRESSED | FAILED
    last_run_note       TEXT,
    next_run_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_schedule_active ON alert_schedule (active, next_run_at);

-- ========================================================== delivery channels
-- Extensible on purpose: EMAIL is the only implementation now, but the notifier resolves
-- a channel by `kind` and every other table here references the channel rather than an
-- email address. Adding Slack is a new implementation plus a row, and touches nothing else.
CREATE TABLE IF NOT EXISTS notification_channel (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    kind        TEXT NOT NULL,              -- EMAIL | SLACK | WEBHOOK | ...
    name        TEXT NOT NULL,
    -- kind-specific settings (SMTP host, webhook URL, ...). JSONB so a new channel type
    -- needs no migration.  NEVER put credentials here — they belong in the app config.
    config      JSONB NOT NULL DEFAULT '{}'::jsonb,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ================================================================ recipients
CREATE TABLE IF NOT EXISTS recipient (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    display_name  TEXT,
    -- The business unit this person is scoped to. NULL = the whole group.
    -- A line manager should not receive another BU's numbers just because they subscribed.
    business_unit TEXT,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================= subscriptions
-- The many-to-many join: one recipient can subscribe to several alerts, and one alert can
-- go to several recipients — over different channels.
--
-- persona_id is stored on the subscription rather than inferred from the alert, because a
-- person can legitimately hold two hats: the same address might receive the facilities-head
-- briefing AND a line-manager alert for their own team, and each should read in the voice
-- of the persona it was sent as.
CREATE TABLE IF NOT EXISTS alert_subscription (
    id                  BIGSERIAL PRIMARY KEY,
    alert_definition_id BIGINT NOT NULL REFERENCES alert_definition(id) ON DELETE CASCADE,
    recipient_id        BIGINT NOT NULL REFERENCES recipient(id) ON DELETE CASCADE,
    channel_id          BIGINT NOT NULL REFERENCES notification_channel(id),
    persona_id          BIGINT NOT NULL REFERENCES persona(id),
    -- Narrow this subscription further than the alert's own scope, e.g. one BU only.
    business_unit       TEXT,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (alert_definition_id, recipient_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_sub_alert ON alert_subscription (alert_definition_id, active);

-- =========================================================== generated reports
-- Every report ever produced, kept whether or not it was sent.
--
-- Keeping suppressed reports matters: "we looked and there was nothing to say" is itself
-- evidence, and without it you cannot tell a quiet month from a broken scheduler.
CREATE TABLE IF NOT EXISTS generated_report (
    id                  BIGSERIAL PRIMARY KEY,
    alert_definition_id BIGINT REFERENCES alert_definition(id),
    persona_id          BIGINT NOT NULL REFERENCES persona(id),
    business_unit       TEXT,               -- NULL = whole group
    period_start        DATE NOT NULL,
    period_end          DATE NOT NULL,
    -- the window this was compared against, so "down 3 points" is always down vs something
    compare_start       DATE,
    compare_end         DATE,

    headline            TEXT NOT NULL,      -- one line, the thing that changed
    body                TEXT NOT NULL,      -- the structured text summary
    recommended_action  TEXT,               -- what this persona should actually do

    -- 0-100. Drives send/suppress and the ordering in the report list.
    severity_score      NUMERIC(5,2) NOT NULL DEFAULT 0,
    actionable          BOOLEAN NOT NULL DEFAULT FALSE,
    status              TEXT NOT NULL DEFAULT 'GENERATED',  -- GENERATED|SENT|SUPPRESSED|FAILED

    -- Who wrote the prose. Deterministic today; LLM in Phase 2. Recorded per report so a
    -- report can always be traced to the thing that produced it, and so the two can be
    -- compared side by side rather than silently swapped.
    generated_by        TEXT NOT NULL DEFAULT 'RULES',      -- RULES | LLM | RULES+LLM
    model_name          TEXT,
    prompt_version      INTEGER,

    -- The full structured payload the text was written from. This is what a Phase 2 LLM
    -- gets handed when a user asks "why did that drop?" — it answers from the same facts
    -- the report was built on, not from a fresh trip through the raw data.
    facts               JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_report_period
    ON generated_report (persona_id, period_end DESC);
CREATE INDEX IF NOT EXISTS idx_report_alert
    ON generated_report (alert_definition_id, created_at DESC);
-- lets an LLM (or the UI) filter on any fact key without a schema change
CREATE INDEX IF NOT EXISTS idx_report_facts ON generated_report USING GIN (facts);

-- ============================================================== report facts
-- One row per metric that appears in a report: the value, what it was judged against, and
-- how much it moved.
--
-- Flattened out of `facts` JSONB on purpose. The JSONB is what an LLM reads; these rows are
-- what a human queries and charts. Both are written at the same time from the same object,
-- so they cannot disagree.
--
-- evidence_sql is the point of the table. Every number carries the query that produced it,
-- which means a claim in a report can always be re-run and checked — by a reviewer today,
-- and by an LLM in Phase 2 that must never be allowed to invent a figure.
CREATE TABLE IF NOT EXISTS report_fact (
    id                BIGSERIAL PRIMARY KEY,
    report_id         BIGINT NOT NULL REFERENCES generated_report(id) ON DELETE CASCADE,
    metric_id         TEXT NOT NULL,        -- ota | cost_per_trip | penalty_exposure | ...
    dimension         TEXT,                 -- vendor | product_type | shift_type | office
    dimension_value   TEXT,

    value             NUMERIC(14,4),
    unit              TEXT,                 -- percent | currency | rate | rating | count
    sample_size       BIGINT,               -- never report a rate without its denominator

    -- what it was compared against, and which kind of reference that is
    reference_value   NUMERIC(14,4),
    reference_kind    TEXT,                 -- SLA | TARGET | PRIOR_PERIOD | PEER | BENCHMARK
    reference_label   TEXT,                 -- e.g. "vendor contract 97% within 5 min"
    delta             NUMERIC(14,4),
    direction         TEXT,                 -- UP | DOWN | FLAT
    verdict           TEXT,                 -- MET | AT_RISK | BREACH | INFO

    -- how much this fact contributed to the report's severity
    contribution      NUMERIC(6,2),
    -- the exact query behind the number
    evidence_sql      TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_report ON report_fact (report_id);
CREATE INDEX IF NOT EXISTS idx_fact_metric ON report_fact (metric_id, created_at DESC);

-- ========================================================== notification log
-- What was actually sent, to whom, over what, and whether it worked.
-- Separate from generated_report because one report fans out to many recipients, and a
-- failure to reach one of them must not look like a failure to produce the report.
CREATE TABLE IF NOT EXISTS notification_log (
    id             BIGSERIAL PRIMARY KEY,
    report_id      BIGINT NOT NULL REFERENCES generated_report(id) ON DELETE CASCADE,
    subscription_id BIGINT REFERENCES alert_subscription(id) ON DELETE SET NULL,
    channel_kind   TEXT NOT NULL,
    target         TEXT NOT NULL,           -- the email address / webhook actually used
    status         TEXT NOT NULL,           -- SENT | FAILED | SKIPPED
    error          TEXT,
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notif_report ON notification_log (report_id);

-- ============================================================ report feedback
-- Phase 2, but the table exists now so feedback can start accumulating from day one —
-- prompts cannot be tuned per persona later without a record of what landed badly, and
-- that record has to be collected before it is needed.
CREATE TABLE IF NOT EXISTS report_feedback (
    id            BIGSERIAL PRIMARY KEY,
    report_id     BIGINT NOT NULL REFERENCES generated_report(id) ON DELETE CASCADE,
    recipient_id  BIGINT REFERENCES recipient(id) ON DELETE SET NULL,
    persona_id    BIGINT REFERENCES persona(id),
    rating        SMALLINT,                 -- -1 unhelpful | 0 neutral | 1 helpful
    -- Which part missed. Vague feedback cannot improve a prompt; this is the minimum
    -- structure that makes it usable.
    aspect        TEXT,                     -- ACCURACY | RELEVANCE | ACTIONABILITY | TONE | LENGTH
    comment       TEXT,
    -- what the report said at the time, so feedback stays meaningful after a reword
    prompt_version INTEGER,
    generated_by   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_feedback_report ON report_feedback (report_id);
CREATE INDEX IF NOT EXISTS idx_feedback_persona ON report_feedback (persona_id, created_at DESC);

-- ================================================================== seeding
-- The three personas from the brief. Only the facilities head is wired to an alert today;
-- the other two exist so the schema and the UI are not reshaped when they are added.
INSERT INTO persona (code, name, description, decision_rights, prompt_template) VALUES
('FACILITIES_HEAD', 'Transport & Facilities Head',
 'Strategic. Owns budget, SLA accountability, vendor strategy and leadership reporting. '
 'Needs a coherent cost / safety / experience story without assembling it manually.',
 'Renegotiate or exit a vendor contract; reallocate volume between vendors; approve or cut '
 'budget; set SLA targets; escalate safety to leadership.',
 'You are writing a monthly briefing for a Transport & Facilities Head who owns the budget '
 'and the vendor contracts. Lead with money and contractual exposure, then safety, then '
 'experience. Every number must carry its reference point — an SLA, a target, the prior '
 'period, or a peer. Recommend only actions this person can actually authorise. Never '
 'state a figure that is not in the supplied facts. If a fact rests on an unconfirmed '
 'assumption, say so plainly rather than smoothing over it.'),
('TRANSPORT_MANAGER', 'Transport Manager',
 'Operational. Owns day-to-day ops — vendor coordination, escalations, shift planning, '
 'delay management. Needs fast, actionable signals, not reports.',
 'Reroute trips; reassign vendors for a shift; chase a driver or vendor; adjust routing; '
 'escalate a sudden break to the vendor same-day.',
 'You are writing a same-day operational signal for a Transport Manager. Lead with what '
 'broke or is sliding and the slice driving it. Distinguish a SUDDEN step (an incident to '
 'chase today) from an INCREMENTAL slide (a trend to get ahead of). Recommend a concrete '
 'operational move — reassign, reroute, chase a vendor. Be terse; this is read in thirty '
 'seconds. Use only the supplied numbers and never invent a figure.'),
('LINE_MANAGER', 'Team / Line Manager',
 'Team-level. Cares about their own people getting to work safely and on time.',
 'Change a team shift time; escalate a rider issue; arrange an exception.',
 NULL)
ON CONFLICT (code) DO NOTHING;

INSERT INTO notification_channel (code, kind, name, config) VALUES
('email-default', 'EMAIL', 'Default email',
 '{"from": "routemind@example.com", "subjectPrefix": "[RouteMind]"}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO alert_definition (code, name, description, persona_id, generator_key,
                              lookback_days, compare_days, send_only_if_actionable)
SELECT 'facilities_head_briefing',
       'Facilities head briefing',
       'One coherent cost, SLA, safety and experience story for the period, with the '
       'vendors and cab types driving each, and a recommended action.',
       p.id, 'facilities_head_briefing', 30, 30, FALSE
FROM persona p WHERE p.code = 'FACILITIES_HEAD'
ON CONFLICT (code) DO NOTHING;

-- Monthly on the 1st at 08:00. The facilities head is a strategic persona — a daily
-- briefing to someone who acts on contract cycles is noise, not service.
INSERT INTO alert_schedule (alert_definition_id, frequency, cron_expression, timezone)
SELECT a.id, 'MONTHLY', '0 0 8 1 * *', 'Asia/Kolkata'
FROM alert_definition a WHERE a.code = 'facilities_head_briefing'
  AND NOT EXISTS (SELECT 1 FROM alert_schedule s WHERE s.alert_definition_id = a.id);

-- The transport manager's operational signal. Short lookback, compared against the
-- matching window before it, and it stays quiet when nothing is degrading — a daily "all
-- stable" would train the reader to ignore it.
INSERT INTO alert_definition (code, name, description, persona_id, generator_key,
                              lookback_days, compare_days, send_only_if_actionable)
SELECT 'transport_manager_signals',
       'Transport manager signals',
       'Every metric that is degrading this window, classified sudden vs incremental, '
       'ranked by urgency, each with the slice driving it and — for OTA — the full '
       'decomposition of why.',
       p.id, 'transport_manager_signals', 7, 7, TRUE
FROM persona p WHERE p.code = 'TRANSPORT_MANAGER'
ON CONFLICT (code) DO NOTHING;

-- Every weekday at 07:00, before the morning shift the manager most needs to act on.
INSERT INTO alert_schedule (alert_definition_id, frequency, cron_expression, timezone)
SELECT a.id, 'DAILY', '0 0 7 * * MON-FRI', 'Asia/Kolkata'
FROM alert_definition a WHERE a.code = 'transport_manager_signals'
  AND NOT EXISTS (SELECT 1 FROM alert_schedule s WHERE s.alert_definition_id = a.id);
