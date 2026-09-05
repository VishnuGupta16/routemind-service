package com.routemind.sla;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of vendor × cab type × shift band combinations that are actually running,
 * and the SLA each one is measured against.
 *
 * This is the onboarding surface: an operator opens a vendor, sees every combination it
 * operates with the current on-time rate beside it, and sets a target per combination.
 * It is also the scorecard, because the same rows carry the verdict once a target exists.
 */
@Service
public class VendorFleetService {

    private static final RowMapper<VendorFleet> MAPPER = (ResultSet rs, int i) -> new VendorFleet(
            rs.getLong("id"), rs.getString("business_unit"), rs.getString("vendor"),
            rs.getString("product_type"), rs.getString("shift_band"),
            rs.getLong("trips"), rs.getInt("vehicles"),
            date(rs, "first_seen"), date(rs, "last_seen"),
            (Double) rs.getObject("observed_ota"), (Double) rs.getObject("avg_delay_min"),
            null, null);

    private static LocalDate date(ResultSet rs, String c) throws SQLException {
        java.sql.Date d = rs.getDate(c);
        return d == null ? null : d.toLocalDate();
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SlaPolicyService policies;

    public VendorFleetService(NamedParameterJdbcTemplate jdbc, SlaPolicyService policies) {
        this.jdbc = jdbc;
        this.policies = policies;
    }

    /** Rebuild from the trip data. Idempotent — run it after every monthly load. */
    public int refresh() {
        Integer n = jdbc.getJdbcTemplate().queryForObject(
                "SELECT refresh_vendor_fleet()", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Every operating combination, each with the SLA that actually applies to it and the
     * resulting verdict.
     *
     * Resolution runs per row rather than once, because that is the whole point: two rows
     * for the same vendor can legitimately resolve to different contracts — a bus in the
     * morning and a cab at night are not the same commitment.
     */
    public List<VendorFleet> all(String vendor, String businessUnit, LocalDate on) {
        String sql = """
                SELECT * FROM vendor_fleet
                WHERE (:vendor IS NULL OR vendor = :vendor)
                  AND (:bu IS NULL OR business_unit = :bu)
                ORDER BY vendor, product_type, shift_band
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("vendor", vendor).addValue("bu", businessUnit);

        LocalDate when = on == null ? LocalDate.now() : on;
        List<VendorFleet> out = new ArrayList<>();
        for (VendorFleet f : jdbc.query(sql, p, MAPPER)) {
            out.add(f.withSla(policies.resolve(f.businessUnit(), f.vendor(), f.productType(),
                    null, f.shiftBand(), when).orElse(null)));
        }
        return out;
    }

    /** Distinct vendors, for the onboarding picker. */
    public List<String> vendors() {
        return jdbc.queryForList("SELECT DISTINCT vendor FROM vendor_fleet ORDER BY vendor",
                new MapSqlParameterSource(), String.class);
    }

    /** The cab types and shift bands a given vendor actually runs. */
    public Map<String, Object> operatingScope(String vendor) {
        MapSqlParameterSource p = new MapSqlParameterSource("vendor", vendor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", vendor);
        out.put("productTypes", jdbc.queryForList(
                "SELECT DISTINCT product_type FROM vendor_fleet WHERE vendor = :vendor "
                        + "ORDER BY product_type", p, String.class));
        out.put("shiftBands", jdbc.queryForList(
                "SELECT DISTINCT shift_band FROM vendor_fleet WHERE vendor = :vendor "
                        + "ORDER BY shift_band", p, String.class));
        out.put("businessUnits", jdbc.queryForList(
                "SELECT DISTINCT business_unit FROM vendor_fleet WHERE vendor = :vendor "
                        + "ORDER BY business_unit", p, String.class));
        return out;
    }

    /**
     * Coverage: how much of the operation is running on a real contract rather than on the
     * group default.
     *
     * Worth surfacing on its own. "97% of your trips are being judged against a placeholder
     * we invented" is a more useful thing for a facilities head to learn than any single
     * vendor's score, and nothing else in the system would tell them.
     */
    public Map<String, Object> coverage(LocalDate on) {
        List<VendorFleet> fleet = all(null, null, on);
        long configured = 0, trips = 0, tripsConfigured = 0, judgeable = 0;
        Map<String, Long> verdicts = new LinkedHashMap<>();
        for (SlaPolicy.Verdict v : SlaPolicy.Verdict.values()) verdicts.put(v.name(), 0L);

        for (VendorFleet f : fleet) {
            trips += f.trips();
            boolean real = f.appliedSla() != null && f.appliedSla().specificity() > 0;
            if (real) {
                configured++;
                tripsConfigured += f.trips();
            }
            if (f.judgeable()) judgeable++;
            if (f.verdict() != null) verdicts.merge(f.verdict().name(), 1L, Long::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("combinations", fleet.size());
        out.put("judgeable", judgeable);
        out.put("withSpecificSla", configured);
        out.put("onGroupDefault", fleet.size() - configured);
        out.put("tripsCovered", tripsConfigured);
        out.put("trips", trips);
        out.put("tripCoveragePct", trips == 0 ? 0.0
                : Math.round(1000.0 * tripsConfigured / trips) / 10.0);
        out.put("verdicts", verdicts);
        return out;
    }
}
