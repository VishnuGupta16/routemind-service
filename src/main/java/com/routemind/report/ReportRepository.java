package com.routemind.report;

import com.routemind.report.GeneratedReport.Fact;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists reports and the facts behind them.
 *
 * Facts are written twice, deliberately: once as JSONB on the report (what a Phase 2 LLM is
 * handed) and once as rows in {@code report_fact} (what a human queries and charts). Both
 * come from the same object in the same transaction, so they cannot drift apart — which
 * they would within a month if either were populated separately.
 */
@Repository
public class ReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public long save(GeneratedReport r, String status) {
        String sql = """
                INSERT INTO generated_report (
                    alert_definition_id, persona_id, business_unit,
                    period_start, period_end, compare_start, compare_end,
                    headline, body, recommended_action,
                    severity_score, actionable, status, generated_by, facts)
                VALUES (
                    (SELECT id FROM alert_definition WHERE code = :alertCode),
                    (SELECT id FROM persona WHERE code = :personaCode),
                    :bu, :periodStart, :periodEnd, :compareStart, :compareEnd,
                    :headline, :body, :action,
                    :severity, :actionable, :status, :generatedBy, CAST(:facts AS jsonb))
                """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("alertCode", r.alertCode())
                .addValue("personaCode", r.personaCode())
                .addValue("bu", r.businessUnit())
                .addValue("periodStart", r.periodStart())
                .addValue("periodEnd", r.periodEnd())
                .addValue("compareStart", r.compareStart())
                .addValue("compareEnd", r.compareEnd())
                .addValue("headline", r.headline())
                .addValue("body", r.body())
                .addValue("action", r.recommendedAction())
                .addValue("severity", r.severityScore())
                .addValue("actionable", r.actionable())
                .addValue("status", status)
                .addValue("generatedBy", r.generatedBy())
                .addValue("facts", Json.write(r.factsPayload()));

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(sql, p, keys, new String[]{"id"});
        long id = ((Number) keys.getKeys().get("id")).longValue();

        for (Fact f : r.facts()) insertFact(id, f);
        return id;
    }

    private void insertFact(long reportId, Fact f) {
        jdbc.update("""
                INSERT INTO report_fact (report_id, metric_id, dimension, dimension_value,
                    value, unit, sample_size, reference_value, reference_kind,
                    reference_label, delta, direction, verdict, contribution, evidence_sql)
                VALUES (:reportId, :metricId, :dimension, :dimensionValue,
                    :value, :unit, :sampleSize, :referenceValue, :referenceKind,
                    :referenceLabel, :delta, :direction, :verdict, :contribution, :evidenceSql)
                """, new MapSqlParameterSource()
                .addValue("reportId", reportId)
                .addValue("metricId", f.metricId())
                .addValue("dimension", f.dimension())
                .addValue("dimensionValue", f.dimensionValue())
                .addValue("value", f.value())
                .addValue("unit", f.unit())
                .addValue("sampleSize", f.sampleSize())
                .addValue("referenceValue", f.referenceValue())
                .addValue("referenceKind", f.referenceKind())
                .addValue("referenceLabel", f.referenceLabel())
                .addValue("delta", f.delta())
                .addValue("direction", f.direction())
                .addValue("verdict", f.verdict())
                .addValue("contribution", f.contribution())
                .addValue("evidenceSql", f.evidenceSql()));
    }

    public void markStatus(long reportId, String status) {
        jdbc.update("UPDATE generated_report SET status = :s WHERE id = :id",
                new MapSqlParameterSource("s", status).addValue("id", reportId));
    }

    /** Report history for the viewer. Body included so the UI needs no second call. */
    public List<Map<String, Object>> history(String personaCode, String businessUnit, int limit) {
        return jdbc.queryForList("""
                SELECT r.id, r.headline, r.body, r.recommended_action, r.severity_score,
                       r.actionable, r.status, r.generated_by, r.business_unit,
                       r.period_start, r.period_end, r.created_at,
                       p.code AS persona_code, p.name AS persona_name,
                       a.code AS alert_code
                FROM generated_report r
                JOIN persona p ON p.id = r.persona_id
                LEFT JOIN alert_definition a ON a.id = r.alert_definition_id
                WHERE (CAST(:persona AS text) IS NULL OR p.code = :persona)
                  AND (CAST(:bu AS text) IS NULL OR r.business_unit = :bu)
                ORDER BY r.created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource("persona", personaCode)
                .addValue("bu", businessUnit).addValue("limit", limit));
    }

    /**
     * The facts behind one report — the exact set a Phase 2 LLM would answer "why?" from.
     * Returned with evidence_sql so any figure can be re-run and checked.
     */
    public List<Map<String, Object>> facts(long reportId) {
        return jdbc.queryForList("""
                SELECT metric_id, dimension, dimension_value, value, unit, sample_size,
                       reference_value, reference_kind, reference_label, delta, direction,
                       verdict, contribution, evidence_sql
                FROM report_fact WHERE report_id = :id
                ORDER BY contribution DESC NULLS LAST, metric_id
                """, new MapSqlParameterSource("id", reportId));
    }

    /**
     * Minimal JSON writer.
     *
     * Spring Boot 4 moved Jackson to the `tools.jackson` package; importing it directly
     * here would tie this file to that migration for the sake of one small object. The
     * payload is a flat map of strings, numbers, booleans, lists and nested maps, so
     * writing it by hand is a dozen lines and no dependency at all.
     */
    static final class Json {
        private Json() {}

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            append(sb, o);
            return sb.toString();
        }

        private static void append(StringBuilder sb, Object o) {
            switch (o) {
                case null -> sb.append("null");
                case String s -> quote(sb, s);
                case Number n -> sb.append(n);
                case Boolean b -> sb.append(b);
                case LocalDate d -> quote(sb, d.toString());
                case Map<?, ?> m -> {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> e : new LinkedHashMap<>(m).entrySet()) {
                        if (!first) sb.append(',');
                        quote(sb, String.valueOf(e.getKey()));
                        sb.append(':');
                        append(sb, e.getValue());
                        first = false;
                    }
                    sb.append('}');
                }
                case Iterable<?> it -> {
                    sb.append('[');
                    boolean first = true;
                    for (Object x : it) {
                        if (!first) sb.append(',');
                        append(sb, x);
                        first = false;
                    }
                    sb.append(']');
                }
                default -> quote(sb, String.valueOf(o));
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
