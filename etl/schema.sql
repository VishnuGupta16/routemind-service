-- RouteMind — Postgres schema for the normalised MoveInSync dataset.
-- Types are real types (BIGINT / TIMESTAMPTZ / NUMERIC / BOOLEAN), not text,
-- so metrics are computed in SQL without per-query parsing.

DROP TABLE IF EXISTS alerts, feedback, billing, trip_employees, trips CASCADE;

-- ---------------------------------------------------------------- trips
-- one row per trip  (source: Ride_data _trip-{may,June,July}_2026.csv)
--
-- NOTE: trip_id is unique WITHIN each monthly file but NOT across them —
-- 6,753 ids collide between months (5,843 May/July, 910 June/July). Making
-- trip_id the primary key would silently discard 1.1% of real trips, so the PK
-- is a surrogate and trip_id is merely indexed. Joins on trip_id alone can
-- therefore fan out slightly; add trip_date to the join when precision matters.
CREATE TABLE trips (
    id                   BIGSERIAL PRIMARY KEY,
    trip_id              BIGINT NOT NULL,
    business_unit        TEXT NOT NULL,
    office               TEXT,
    product_type         TEXT,              -- CAB | BUS | SPOT_2.0
    trip_date            DATE,
    shift_type           TEXT,              -- clock time, e.g. '11:00'; also 'Non Shift'/'Adhoc'
    -- 100 distinct shift times grouped into 6 operational bands, because SLAs cannot be
    -- configured against 100 clock times. They separate real performance: MORNING runs at
    -- 92.3% on time, EARLY at 99.4%. 'Non Shift'/'Adhoc' become UNSCHEDULED — 14,799 trips
    -- at 86.9%, the worst band, which folding them into a time band would have hidden.
    shift_band           TEXT,              -- NIGHT|EARLY|MORNING|MIDDAY|EVENING|UNSCHEDULED
    trip_direction       TEXT,              -- LOGIN | LOGOUT
    vendor               TEXT,
    actual_escort        BOOLEAN,
    planned_cab_reg      TEXT,
    actual_cab_reg       TEXT,
    actual_cab_capacity  INTEGER,
    planned_km           NUMERIC(10,3),
    traveled_km          NUMERIC(10,3),
    planned_start        TIMESTAMPTZ,
    planned_end          TIMESTAMPTZ,
    actual_start         TIMESTAMPTZ,
    actual_end           TIMESTAMPTZ,
    delay_reason         TEXT,              -- NODELAY | TRAFFIC | EMPLOYEE | ...
    delay_minutes        INTEGER,
    route_source         TEXT,              -- AUTO | MANUAL | SHUTTLE_SERVICE
    fuel_type            TEXT,              -- Petrol | Diesel | Electric
    is_driver_nc         BOOLEAN,           -- driver non-compliance
    is_cab_nc            BOOLEAN,           -- cab non-compliance
    trip_nodal           TEXT,              -- HOME | NODAL | SHUTTLE
    planned_employee_cnt INTEGER,
    actual_employee_cnt  INTEGER,
    noshow_cnt           INTEGER
);

-- ------------------------------------------------------- trip_employees
-- one row per employee per trip  (source: emp_Data.csv)
CREATE TABLE trip_employees (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT,
    stwid               BIGINT,             -- employee id (NULL where source was 0)
    business_unit       TEXT,
    office              TEXT,
    product_type        TEXT,
    trip_date           DATE,
    shift_type          TEXT,
    planned_pickup      TIMESTAMPTZ,
    planned_drop        TIMESTAMPTZ,
    actual_pickup       TIMESTAMPTZ,        -- ~12% NULL in source
    actual_drop         TIMESTAMPTZ,
    planned_km          NUMERIC(10,3),
    traveled_km         NUMERIC(10,3),
    signintype          TEXT,               -- Planned | Adhoc | Guest
    gender              TEXT,
    emp_role            TEXT,
    boarding_status     TEXT,               -- Boarded | Not Boarded
    not_boarding_reason TEXT,               -- NO_SHOW | TRIP_CANCELLED_FROM_DASHBOARD
    is_no_show          BOOLEAN
);

-- --------------------------------------------------------------- billing
-- one row per billed line  (source: bill_data.csv)
CREATE TABLE billing (
    id            BIGSERIAL PRIMARY KEY,
    trip_id       BIGINT,                   -- NULL when the line is a monthly one
    is_overhead   BOOLEAN NOT NULL DEFAULT FALSE,
    -- 189 lines carry a NEGATIVE amount: SLA PENALTIES deducted from the vendor
    -- (confirmed by the product owner). Flagged so gross billing, penalties and
    -- net spend can each be reported, and so penalties can be analysed on their own.
    is_penalty    BOOLEAN NOT NULL DEFAULT FALSE,
    -- is_overhead alone conflates two different things, so lines are also classified:
    --   TRIP             positive line against a real trip
    --   TRIP_PENALTY     negative line against a real trip   (156 lines, -14.67M)
    --   MONTHLY_PENALTY  negative monthly line                ( 33 lines,  -0.83M)
    --   FIXED_CHARGE     positive monthly line                (127 lines,  +5.29M)
    -- Penalties therefore come in TWO forms, per-trip and monthly, and total vendor
    -- penalty exposure is TRIP_PENALTY + MONTHLY_PENALTY = 189 lines, -15.50M.
    -- A FIXED_CHARGE is money paid TO the vendor and so cannot be a penalty on them;
    -- what those lines buy is still unconfirmed (open-questions.md A2).
    line_kind     TEXT,
    business_unit TEXT,
    office        TEXT,
    vendor        TEXT,
    cycle_start   DATE,
    cycle_end     DATE,
    contract      TEXT,
    slab_name     TEXT,
    total_trip_km NUMERIC(10,3),
    trip_cost     NUMERIC(12,2)
);

-- -------------------------------------------------------------- feedback
-- one row per feedback response  (source: trip_feedback.csv)
-- rating 0 in the source means "not rated" and is stored as NULL.
CREATE TABLE feedback (
    id             BIGSERIAL PRIMARY KEY,
    trip_id        BIGINT,
    stwid          BIGINT,
    business_unit  TEXT,
    trip_type      TEXT,                    -- LOGIN | LOGOUT
    trip_at        TIMESTAMPTZ,             -- source 'trip_date' is a datetime
    route_rating   SMALLINT,
    driver_rating  SMALLINT,
    cab_rating     SMALLINT,
    safety_rating  SMALLINT,
    marshal_rating SMALLINT,
    created_at     TIMESTAMPTZ
);

-- ---------------------------------------------------------------- alerts
-- one row per safety/ops event  (source: alerts_data.csv)
CREATE TABLE alerts (
    event_id         TEXT PRIMARY KEY,
    trip_id          BIGINT,
    stwid            BIGINT,
    business_unit    TEXT,
    event_type       TEXT,                  -- WOMAN_TRAVELLING_ALONE, GEOFENCE_VIOLATION, ...
    start_time       TIMESTAMPTZ,
    acknowledge_time TIMESTAMPTZ,
    state_text       TEXT,                  -- NEW | OPEN | CLOSED
    severity         TEXT,                  -- Sev-1..3; source 'False'/blank -> NULL
    source           TEXT                   -- ~24% populated
);

-- ------------------------------------------------------------ sla_policy
-- Configurable SLA, set when a vendor is onboarded.
--
-- An SLA is a CONTRACT TERM, so it varies: a premium vendor may commit to 97% within
-- 5 minutes while a shuttle operator commits to 90% within 15. Any scope column left
-- NULL is a wildcard, so you can write one group-wide default and override it only
-- where a contract differs.
--
-- Resolution = most specific match wins:
--     vendor (8) > business_unit (4) > product_type (2) > shift_type (1)
-- ties broken by `priority`, then by the most recent `effective_from`.
--
-- Shift scoping is by EXACT shift time. `trips.shift_band` exists for reporting, but a
-- contract commits to a clock time, not to a bucket we invented, so it is not a scope
-- column here.
--
-- effective_from/to make it temporal: renegotiating a contract adds a new row rather
-- than overwriting history, so past months are still scored against the SLA that was
-- actually in force at the time.
CREATE TABLE IF NOT EXISTS sla_policy (
    id                  BIGSERIAL PRIMARY KEY,
    name                TEXT NOT NULL,
    business_unit       TEXT,               -- NULL = any tenant
    vendor              TEXT,               -- NULL = any vendor
    product_type        TEXT,               -- NULL = any cab type (CAB|BUS|SPOT_2.0)
    shift_type          TEXT,               -- NULL = any; an EXACT shift time, e.g. '09:30'
    ota_window_minutes  INTEGER NOT NULL DEFAULT 10,   -- minutes late still counted on time
    ota_target          NUMERIC(5,2) NOT NULL DEFAULT 95.0,
    -- The +/- deviation allowed around the target before the verdict changes.
    --   value >= target                          -> MET
    --   target - tolerance <= value < target      -> AT_RISK   (inside the band)
    --   value < target - tolerance                -> BREACH
    -- Per policy, not global: a 97% premium contract and a 90% shuttle contract do not
    -- deserve the same tolerance. NULL falls back to routemind.sla.at-risk-margin.
    tolerance_pct       NUMERIC(4,2),
    no_show_target      NUMERIC(5,2),
    priority            INTEGER NOT NULL DEFAULT 0,    -- tie-breaker
    effective_from      DATE,
    effective_to        DATE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          TEXT
);

CREATE INDEX IF NOT EXISTS idx_sla_scope
    ON sla_policy (business_unit, vendor, product_type, shift_type) WHERE active;

-- Group-wide fallback so the system always resolves to something.
INSERT INTO sla_policy (name, ota_window_minutes, ota_target, tolerance_pct, priority,
                        notes, created_by)
SELECT 'Group default', 10, 95.0, 2.0, -100,
       'Fallback when no vendor/type/shift specific SLA applies. Replace with the real '
       'contractual terms during vendor onboarding.', 'system'
WHERE NOT EXISTS (SELECT 1 FROM sla_policy WHERE name = 'Group default');

-- ---------------------------------------------------------- vendor_fleet
-- Every vendor x cab type x shift time combination that is ACTUALLY OPERATING, with the
-- observed volume and performance of each. 1,665 of them on this dataset.
--
-- Why this exists: an SLA has to be configured against something real. Without this table
-- the onboarding screen is five free-text boxes, and nothing stops someone committing a
-- vendor to a 97% target on a cab type they have never run — a target that can never be
-- measured, and a scorecard that quietly reads "no data" forever. Here the operator picks
-- from combinations the data says exist, and sees the current on-time rate while choosing
-- the target, so the number they commit to is informed rather than invented.
--
-- Derived, not entered. Refresh it after every load with refresh_vendor_fleet().
CREATE TABLE IF NOT EXISTS vendor_fleet (
    id             BIGSERIAL PRIMARY KEY,
    business_unit  TEXT NOT NULL,
    vendor         TEXT NOT NULL,
    product_type   TEXT NOT NULL,          -- CAB | BUS | SPOT_2.0
    shift_type     TEXT NOT NULL,          -- exact shift time, e.g. '09:30'
    trips          BIGINT NOT NULL,
    vehicles       INTEGER NOT NULL,       -- distinct registrations seen
    first_seen     DATE,
    last_seen      DATE,
    observed_ota   NUMERIC(5,2),           -- at the DEFAULT 10-minute window, for reference
    avg_delay_min  NUMERIC(6,2),
    refreshed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_unit, vendor, product_type, shift_type)
);

CREATE INDEX IF NOT EXISTS idx_fleet_vendor ON vendor_fleet (vendor, product_type, shift_type);

-- Rebuild from the trip data. Cheap (one scan) and idempotent, so it can run after
-- every monthly load.
CREATE OR REPLACE FUNCTION refresh_vendor_fleet() RETURNS INTEGER AS $$
DECLARE n INTEGER;
BEGIN
    TRUNCATE vendor_fleet;
    INSERT INTO vendor_fleet (business_unit, vendor, product_type, shift_type, trips,
                              vehicles, first_seen, last_seen, observed_ota, avg_delay_min)
    SELECT business_unit, vendor, product_type,
           COALESCE(shift_type, 'Unknown'),
           count(*),
           count(DISTINCT actual_cab_reg),
           min(trip_date), max(trip_date),
           round(100.0 * count(*) FILTER (WHERE delay_minutes <= 10)
                 / NULLIF(count(*), 0), 2),
           -- delays are winsorised at 240 min: the raw max is 10,644 (7.4 days), which is
           -- an artefact that would inflate this average by roughly 15%
           round(avg(least(delay_minutes, 240)), 2)
    FROM trips
    WHERE vendor IS NOT NULL AND product_type IS NOT NULL
    GROUP BY business_unit, vendor, product_type, COALESCE(shift_type, 'Unknown');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------- indexes
CREATE INDEX idx_trips_trip_id    ON trips (trip_id);
CREATE INDEX idx_trips_date       ON trips (trip_date);
CREATE INDEX idx_trips_vendor     ON trips (vendor);
CREATE INDEX idx_trips_bu_office  ON trips (business_unit, office);
CREATE INDEX idx_trips_bu_date    ON trips (business_unit, trip_date);

CREATE INDEX idx_te_trip          ON trip_employees (trip_id);
CREATE INDEX idx_te_stwid         ON trip_employees (stwid);
CREATE INDEX idx_te_date          ON trip_employees (trip_date);

CREATE INDEX idx_bill_trip        ON billing (trip_id);
CREATE INDEX idx_bill_vendor      ON billing (vendor);

CREATE INDEX idx_fb_trip          ON feedback (trip_id);
CREATE INDEX idx_alerts_trip      ON alerts (trip_id);
CREATE INDEX idx_alerts_type      ON alerts (event_type);
