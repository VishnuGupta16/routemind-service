#!/usr/bin/env bash
# Load the normalised CSVs into Postgres.
#   ./load.sh "postgresql://user:pass@localhost:5432/routemind" ./normalized
set -euo pipefail

DSN="${1:?usage: ./load.sh <postgres-dsn> <normalized-dir>}"
DIR="${2:?usage: ./load.sh <postgres-dsn> <normalized-dir>}"

echo "==> creating schema"
psql "$DSN" -v ON_ERROR_STOP=1 -f "$(dirname "$0")/schema.sql"

copy () {  # $1 = table, $2 = file, $3 = column list
  echo "==> loading $1"
  psql "$DSN" -v ON_ERROR_STOP=1 \
    -c "\copy $1 ($3) FROM '$DIR/$2' WITH (FORMAT csv, HEADER true, NULL '')"
}

copy trips trips.csv \
"trip_id,business_unit,office,product_type,trip_date,shift_type,shift_band,trip_direction,\
vendor,actual_escort,planned_cab_reg,actual_cab_reg,actual_cab_capacity,planned_km,traveled_km,\
planned_start,planned_end,actual_start,actual_end,delay_reason,delay_minutes,route_source,\
fuel_type,is_driver_nc,is_cab_nc,trip_nodal,planned_employee_cnt,actual_employee_cnt,noshow_cnt"

copy trip_employees trip_employees.csv \
"trip_id,stwid,business_unit,office,product_type,trip_date,shift_type,planned_pickup,\
planned_drop,actual_pickup,actual_drop,planned_km,traveled_km,signintype,gender,emp_role,\
boarding_status,not_boarding_reason,is_no_show"

copy billing billing.csv \
"trip_id,is_overhead,is_penalty,line_kind,business_unit,office,vendor,cycle_start,cycle_end,\
contract,slab_name,total_trip_km,trip_cost"

copy feedback feedback.csv \
"trip_id,stwid,business_unit,trip_type,trip_at,route_rating,driver_rating,cab_rating,\
safety_rating,marshal_rating,created_at"

copy alerts alerts.csv \
"event_id,trip_id,stwid,business_unit,event_type,start_time,acknowledge_time,state_text,\
severity,source"

echo "==> row counts"
psql "$DSN" -c "SELECT 'trips' t, count(*) FROM trips
UNION ALL SELECT 'trip_employees', count(*) FROM trip_employees
UNION ALL SELECT 'billing', count(*) FROM billing
UNION ALL SELECT 'feedback', count(*) FROM feedback
UNION ALL SELECT 'alerts', count(*) FROM alerts;"

echo "==> done"
