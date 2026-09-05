package com.routemind.metrics.spi;

import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.metrics.spi.MetricDefinition.MetricPoint;
import com.routemind.metrics.spi.MetricDefinition.MetricQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

/** Small SQL helpers shared by every metric definition. */
public final class Sql {

    private Sql() {}

    /** Standard tenant filter — every table carries business_unit. */
    public static final String BU = " AND (CAST(:bu AS text) IS NULL OR business_unit = :bu) ";

    /**
     * Excludes service lines that can't be measured on the same basis (SPOT_2.0).
     * Only valid on tables that HAVE product_type — trips and trip_employees.
     * Billing, alerts and feedback don't carry it, so don't append this there.
     */
    public static final String PRODUCTS = " AND product_type NOT IN (:excludedProducts) ";

    /** Restricts TO the excluded products — used by the metrics that measure them. */
    public static final String ONLY_EXCLUDED = " AND product_type IN (:targetProducts) ";

    public static MapSqlParameterSource params(MetricQuery q) {
        List<String> configured = q.excludedProductTypes() == null
                ? List.of() : q.excludedProductTypes();

        // a sentinel keeps "NOT IN" / "IN" valid even when the list is empty
        List<String> excluded = new java.util.ArrayList<>(configured);
        excluded.add("__none__");

        // `targetProducts` is the inverse view of the same list, for any metric that
        // wants to measure ONLY the excluded types rather than exclude them
        List<String> targets = configured.isEmpty() ? List.of("__none__") : configured;

        return new MapSqlParameterSource()
                .addValue("from", q.from())
                .addValue("to", q.to())
                .addValue("bu", q.businessUnit())
                .addValue("window", q.otaWindowMinutes())
                .addValue("excludedProducts", excluded)
                .addValue("targetProducts", targets);
    }

    /** Runs a query returning exactly columns (value, sample_size). */
    public static MetricPoint point(NamedParameterJdbcTemplate jdbc, String sql, MetricQuery q) {
        return jdbc.query(sql, params(q), rs -> {
            if (!rs.next()) return MetricPoint.EMPTY;
            long n = rs.getLong("sample_size");
            double v = rs.getDouble("value");
            if (rs.wasNull() || n == 0) return new MetricPoint(0, n);
            return new MetricPoint(round1(v), n);
        });
    }

    /** Runs a query returning (member, cnt) ordered desc, converts to % of total. */
    public static List<Contribution> contributions(NamedParameterJdbcTemplate jdbc,
                                                   String sql, MetricQuery q, int topN) {
        MapSqlParameterSource p = params(q).addValue("topN", topN);
        return jdbc.query(sql, p, (rs, i) -> new Contribution(
                rs.getString("member"),
                rs.getLong("cnt"),
                round1(rs.getDouble("pct"))));
    }

    public static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    public static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
