package com.routemind.onboarding;

import com.routemind.onboarding.SourceProfile.ColumnProfile;
import com.routemind.onboarding.SourceProfile.FieldMapping;
import com.routemind.onboarding.SourceProfile.Proposal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Onboarding: discover a source's shape, propose a mapping, and declare which
 * metrics become computable. Missing inputs DISABLE a metric rather than
 * producing a wrong number.
 */
@Service
public class OnboardingService {

    /** metric id -> canonical fields it needs. */
    private static final Map<String, List<String>> REQUIREMENTS = Map.of(
            "ota", List.of("tripId", "plannedStart", "actualStart"),
            "no_show_rate", List.of("tripId", "isNoShow"),
            "cost_per_trip", List.of("tripId", "cost"),
            "experience", List.of("tripId", "rating"),
            "safety_alerts_per_1k", List.of("tripId", "eventType"),
            "seat_utilisation", List.of("tripId", "capacity", "occupancy"),
            "ev_share", List.of("tripId", "fuelType")
    );

    private final FieldIdentifier identifier;
    private final NamedParameterJdbcTemplate jdbc;

    public OnboardingService(FieldIdentifier identifier, NamedParameterJdbcTemplate jdbc) {
        this.identifier = identifier;
        this.jdbc = jdbc;
    }

    /** Profile a set of raw columns + sample rows (as a CSV header + a few rows would give). */
    public Proposal propose(String sourceId, List<ColumnProfile> columns) {
        List<FieldMapping> mappings = identifier.identify(columns);
        List<String> unresolved = identifier.unresolved(columns, mappings);

        Set<String> have = new HashSet<>();
        mappings.forEach(m -> have.add(m.canonicalField()));

        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        REQUIREMENTS.forEach((metric, needs) -> {
            boolean ok = have.containsAll(needs);
            capabilities.put(metric, ok);
            if (!ok) {
                List<String> missing = needs.stream().filter(n -> !have.contains(n)).toList();
                warnings.add(metric + " disabled — missing " + missing);
            }
        });

        // delayMinutes can substitute for planned/actual start
        if (have.contains("delayMinutes") && !capabilities.getOrDefault("ota", false)) {
            capabilities.put("ota", true);
            warnings.removeIf(w -> w.startsWith("ota "));
            warnings.add("ota enabled via precomputed delayMinutes");
        }

        if (!unresolved.isEmpty()) {
            warnings.add("unresolved columns for tier-3 review: " + unresolved);
        }

        return new Proposal(sourceId, mappings, unresolved, capabilities, warnings);
    }

    /** Discover the shape of a table already in our Postgres (used to self-verify). */
    public List<ColumnProfile> discover(String table) {
        String safe = table.replaceAll("[^a-zA-Z0-9_]", "");
        String sql = """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = :t
                ORDER BY ordinal_position
                """;
        return jdbc.query(sql, Map.of("t", safe), (rs, i) ->
                new ColumnProfile(rs.getString("column_name"),
                        rs.getString("data_type").toUpperCase(),
                        100.0, 0, List.of()));
    }
}
