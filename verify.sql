-- Verify the Postgres load.
--   psql "postgresql://routemind:pass@localhost:5432/routemind" -f verify.sql
-- Expected values are from the normalised export — yours should match.

\echo '== 1. row counts (expect: 608793 / 1637906 / 620942 / 512873 / 51699) =='
SELECT 'trips' AS table, count(*) FROM trips
UNION ALL SELECT 'trip_employees', count(*) FROM trip_employees
UNION ALL SELECT 'billing',        count(*) FROM billing
UNION ALL SELECT 'feedback',       count(*) FROM feedback
UNION ALL SELECT 'alerts',         count(*) FROM alerts;

\echo '== 2. date range (expect 2026-05-01 .. 2026-07-31) =='
SELECT min(trip_date) AS from_date, max(trip_date) AS to_date FROM trips;

\echo '== 3. types landed correctly (no text columns for times/numbers) =='
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'trips'
  AND column_name IN ('trip_id','trip_date','planned_start','delay_minutes','planned_km')
ORDER BY column_name;

\echo '== 4. overall OTA (expect ~96.4%) =='
SELECT count(*)                                                  AS trips,
       count(*) FILTER (WHERE delay_minutes > 10)                AS late_trips,
       round(100.0 * count(*) FILTER (WHERE delay_minutes <= 10)
             / NULLIF(count(*),0), 1)                            AS ota_pct
FROM trips;

\echo '== 5. OTA by month — the trend reference (expect 97.2 / 95.2 / 96.8) =='
SELECT date_trunc('month', trip_date)::date AS month,
       round(100.0 * count(*) FILTER (WHERE delay_minutes <= 10)
             / NULLIF(count(*),0), 1) AS ota_pct
FROM trips GROUP BY 1 ORDER BY 1;

\echo '== 6. worst vendors BY RATE (expect worst ~93.2%) =='
SELECT vendor, count(*) AS trips,
       round(100.0 * count(*) FILTER (WHERE delay_minutes <= 10)
             / NULLIF(count(*),0), 1) AS ota_pct
FROM trips GROUP BY vendor HAVING count(*) > 5000
ORDER BY ota_pct ASC LIMIT 5;

\echo '== 7. attribution: who owns the late trips BY VOLUME (expect top ~13.3%) =='
WITH late AS (SELECT vendor FROM trips WHERE delay_minutes > 10)
SELECT vendor, count(*) AS late_trips,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct_of_all_lateness
FROM late GROUP BY vendor ORDER BY late_trips DESC LIMIT 5;

\echo '== 8. joins work (trip_id normalisation) — expect non-zero matches =='
SELECT (SELECT count(*) FROM trips t JOIN trip_employees e USING (trip_id)) AS trips_x_emp,
       (SELECT count(*) FROM trips t JOIN billing b        USING (trip_id)) AS trips_x_bill,
       (SELECT count(*) FROM trips t JOIN feedback f       USING (trip_id)) AS trips_x_feedback,
       (SELECT count(*) FROM trips t JOIN alerts a         USING (trip_id)) AS trips_x_alerts;

\echo '== 9. cleaning applied (all should be > 0 / sane) =='
SELECT (SELECT count(*) FROM billing WHERE is_overhead)              AS overhead_rows,
       (SELECT count(*) FROM alerts  WHERE severity IS NULL)         AS unclassified_severity,
       (SELECT count(*) FROM feedback WHERE marshal_rating IS NULL)  AS marshal_not_rated,
       (SELECT count(*) FROM trip_employees WHERE actual_pickup IS NULL) AS missing_actual_pickup;

\echo '== 10. dimensions available (expect 5 BUs / 17 offices / 23 vendors) =='
SELECT count(DISTINCT business_unit) AS business_units,
       count(DISTINCT office)        AS offices,
       count(DISTINCT vendor)        AS vendors
FROM trips;
