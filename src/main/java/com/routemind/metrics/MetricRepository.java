package com.routemind.metrics;

import com.routemind.metrics.model.Models.TableCount;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Only cross-cutting data access lives here. Per-metric SQL belongs to its own
 * {@link com.routemind.metrics.spi.MetricDefinition} — that is what keeps metrics pluggable.
 */
@Repository
public class MetricRepository {

    private static final List<String> TABLES =
            List.of("trips", "trip_employees", "billing", "feedback", "alerts");

    private final NamedParameterJdbcTemplate jdbc;

    public MetricRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Row counts — verifies the Postgres load. */
    public List<TableCount> tableCounts() {
        List<TableCount> out = new ArrayList<>();
        for (String t : TABLES) {
            Long n = jdbc.getJdbcTemplate()
                    .queryForObject("SELECT count(*) FROM " + t, Long.class);
            out.add(new TableCount(t, n == null ? 0 : n));
        }
        return out;
    }

    /** Distinct tenants, for the UI's business-unit selector. */
    public List<String> businessUnits() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT business_unit FROM trips ORDER BY 1", String.class);
    }

    /** Available data window, so the UI can default its date range sensibly. */
    public List<String> dateRange() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT min(trip_date)::text FROM trips "
                        + "UNION ALL SELECT max(trip_date)::text FROM trips",
                String.class);
    }
}
