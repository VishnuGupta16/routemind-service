package com.routemind.sla;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** CRUD + resolution for vendor-configurable SLAs. */
@Service
public class SlaPolicyService {

    /**
     * The scope-matching clause, reused by resolution and by the metrics themselves.
     * NULL in a policy column means "any", so a group default matches everything.
     */
    public static final String SCOPE_MATCH = """
              (s.business_unit IS NULL OR s.business_unit = %1$s.business_unit)
              AND (s.vendor       IS NULL OR s.vendor       = %1$s.vendor)
              AND (s.product_type IS NULL OR s.product_type = %1$s.product_type)
              AND (s.shift_type   IS NULL OR s.shift_type   = %1$s.shift_type)
              AND s.active
              AND (s.effective_from IS NULL OR s.effective_from <= %1$s.trip_date)
              AND (s.effective_to   IS NULL OR s.effective_to   >= %1$s.trip_date)
            """;

    /**
     * Most specific first: vendor 8 &gt; business unit 4 &gt; product 2 &gt; shift type 1.
     * Kept identical to {@link SlaPolicy#specificity()} — if these two ever disagree, the
     * UI shows one contract and the metrics score against another. {@code SlaPolicyTest}
     * pins them together.
     */
    public static final String SPECIFICITY_ORDER = """
              ORDER BY ( (s.vendor IS NOT NULL)::int * 8
                       + (s.business_unit IS NOT NULL)::int * 4
                       + (s.product_type IS NOT NULL)::int * 2
                       + (s.shift_type IS NOT NULL)::int * 1 ) DESC,
                       s.priority DESC,
                       s.effective_from DESC NULLS LAST
            """;

    /**
     * Correlated sub-select giving each trip its OWN on-time window. Falls back to the
     * caller's default when no policy matches at all.
     */
    public static String windowForTrip(String tripAlias) {
        return "COALESCE((SELECT s.ota_window_minutes FROM sla_policy s WHERE "
                + String.format(SCOPE_MATCH, tripAlias) + SPECIFICITY_ORDER
                + " LIMIT 1), :window)";
    }

    /** The same, for the contractual target rather than the window. */
    public static String targetForTrip(String tripAlias) {
        return "COALESCE((SELECT s.ota_target FROM sla_policy s WHERE "
                + String.format(SCOPE_MATCH, tripAlias) + SPECIFICITY_ORDER
                + " LIMIT 1), :target)";
    }

    private static final RowMapper<SlaPolicy> MAPPER = (ResultSet rs, int i) -> new SlaPolicy(
            rs.getLong("id"), rs.getString("name"),
            rs.getString("business_unit"), rs.getString("vendor"),
            rs.getString("product_type"), rs.getString("shift_type"),
            rs.getInt("ota_window_minutes"), rs.getDouble("ota_target"),
            dbl(rs, "tolerance_pct"),
            dbl(rs, "no_show_target"), rs.getInt("priority"),
            date(rs, "effective_from"), date(rs, "effective_to"),
            rs.getBoolean("active"), rs.getString("notes"), rs.getString("created_by"));

    /** numeric columns arrive as BigDecimal, so read them as such and keep SQL NULL as null. */
    private static Double dbl(ResultSet rs, String c) throws SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(c);
        return v == null ? null : v.doubleValue();
    }

    private static LocalDate date(ResultSet rs, String c) throws SQLException {
        java.sql.Date d = rs.getDate(c);
        return d == null ? null : d.toLocalDate();
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SlaPolicyService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SlaPolicy> all() {
        return jdbc.query("SELECT * FROM sla_policy s " + SPECIFICITY_ORDER + ", s.name",
                new MapSqlParameterSource(), MAPPER);
    }

    /** Which SLA actually applies to this combination on this date. */
    public Optional<SlaPolicy> resolve(String businessUnit, String vendor, String productType,
                                       String shiftType, LocalDate on) {
        String sql = """
                SELECT * FROM sla_policy s
                WHERE (s.business_unit IS NULL OR s.business_unit = :bu)
                  AND (s.vendor        IS NULL OR s.vendor        = :vendor)
                  AND (s.product_type  IS NULL OR s.product_type  = :product)
                  AND (s.shift_type    IS NULL OR s.shift_type    = :shift)
                  AND s.active
                  AND (s.effective_from IS NULL OR s.effective_from <= :on)
                  AND (s.effective_to   IS NULL OR s.effective_to   >= :on)
                """ + SPECIFICITY_ORDER + " LIMIT 1";

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("bu", businessUnit).addValue("vendor", vendor)
                .addValue("product", productType).addValue("shift", shiftType)
                .addValue("on", on == null ? LocalDate.now() : on);
        return jdbc.query(sql, p, MAPPER).stream().findFirst();
    }

    public SlaPolicy create(SlaPolicy p) {
        String sql = """
                INSERT INTO sla_policy (name, business_unit, vendor, product_type, shift_type,
                                        ota_window_minutes, ota_target, tolerance_pct,
                                        no_show_target, priority, effective_from,
                                        effective_to, active, notes, created_by)
                VALUES (:name, :bu, :vendor, :product, :shift, :window, :target,
                        :tolerance, :noShow, :priority, :from, :to, :active, :notes, :by)
                RETURNING *
                """;
        return jdbc.query(sql, params(p), MAPPER).get(0);
    }

    public Optional<SlaPolicy> update(long id, SlaPolicy p) {
        String sql = """
                UPDATE sla_policy SET name=:name, business_unit=:bu, vendor=:vendor,
                    product_type=:product, shift_type=:shift, ota_window_minutes=:window, ota_target=:target, tolerance_pct=:tolerance,
                    no_show_target=:noShow, priority=:priority,
                    effective_from=:from, effective_to=:to, active=:active, notes=:notes
                WHERE id=:id RETURNING *
                """;
        return jdbc.query(sql, params(p).addValue("id", id), MAPPER).stream().findFirst();
    }

    public boolean deactivate(long id) {
        return jdbc.update("UPDATE sla_policy SET active = FALSE WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    private MapSqlParameterSource params(SlaPolicy p) {
        return new MapSqlParameterSource()
                .addValue("name", p.name() == null ? "Unnamed SLA" : p.name())
                .addValue("bu", blankToNull(p.businessUnit()))
                .addValue("vendor", blankToNull(p.vendor()))
                .addValue("product", blankToNull(p.productType()))
                .addValue("shift", blankToNull(p.shiftType()))
                .addValue("window", p.otaWindowMinutes() <= 0 ? 10 : p.otaWindowMinutes())
                .addValue("target", p.otaTarget() <= 0 ? 95.0 : p.otaTarget())
                .addValue("tolerance", p.tolerancePct())
                .addValue("noShow", p.noShowTarget())
                .addValue("priority", p.priority())
                .addValue("from", p.effectiveFrom())
                .addValue("to", p.effectiveTo())
                .addValue("active", p.active())
                .addValue("notes", p.notes())
                .addValue("by", p.createdBy() == null ? "operator" : p.createdBy());
    }

    /** Empty strings from a form mean "any", not a literal empty scope. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
