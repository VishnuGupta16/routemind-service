package com.routemind.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SQL fallback is the one place the model's output reaches the database, so the fence
 * around it is tested directly rather than trusted.
 */
class SafeSqlExecutorTest {

    private static final String OK_SQL = """
            SELECT t.vendor, count(*) AS late
            FROM trips t
            WHERE t.trip_date BETWEEN :from AND :to
              AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
              AND t.delay_minutes > 10
            GROUP BY t.vendor ORDER BY late DESC LIMIT 20""";

    @Test
    @DisplayName("a well-formed, scoped aggregate is accepted")
    void acceptsGoodQuery() {
        assertNull(SafeSqlExecutor.validate(OK_SQL));
    }

    @Test
    @DisplayName("writes are refused however they are phrased")
    void refusesWrites() {
        assertNotNull(SafeSqlExecutor.validate(
                "DELETE FROM trips WHERE trip_date BETWEEN :from AND :to AND :bu IS NULL"));
        assertNotNull(SafeSqlExecutor.validate(
                "UPDATE trips SET vendor='x' WHERE trip_date BETWEEN :from AND :to AND :bu IS NULL"));
        assertNotNull(SafeSqlExecutor.validate(
                "DROP TABLE trips"));
    }

    @Test
    @DisplayName("a chained second statement is refused")
    void refusesStatementChaining() {
        assertEquals("only one statement is allowed", SafeSqlExecutor.validate(
                OK_SQL + "; DROP TABLE trips"));
    }

    @Test
    @DisplayName("system catalogues are not readable")
    void refusesCatalogues() {
        assertNotNull(SafeSqlExecutor.validate(
                "SELECT * FROM pg_shadow WHERE :from IS NOT NULL AND :to IS NOT NULL AND :bu IS NULL"));
        assertNotNull(SafeSqlExecutor.validate(
                "SELECT * FROM information_schema.tables WHERE :from IS NOT NULL "
                        + "AND :to IS NOT NULL AND :bu IS NULL"));
    }

    @Test
    @DisplayName("a table outside the allow-list is refused")
    void refusesUnknownTable() {
        String sql = """
                SELECT * FROM secrets s
                WHERE s.trip_date BETWEEN :from AND :to
                  AND (CAST(:bu AS text) IS NULL OR s.business_unit = :bu)""";
        assertEquals("table 'secrets' is not readable", SafeSqlExecutor.validate(sql));
    }

    @Test
    @DisplayName("an unscoped query is refused — the model cannot widen its own slice")
    void refusesUnscopedQuery() {
        assertNotNull(SafeSqlExecutor.validate("SELECT count(*) FROM trips"));
        // period present but no business-unit guard
        assertEquals("must include the business-unit guard on :bu", SafeSqlExecutor.validate(
                "SELECT count(*) FROM trips t WHERE t.trip_date BETWEEN :from AND :to"));
    }

    @Test
    @DisplayName("connection-holding functions are refused")
    void refusesSleep() {
        assertNotNull(SafeSqlExecutor.validate(
                "SELECT pg_sleep(60) FROM trips WHERE trip_date BETWEEN :from AND :to AND :bu IS NULL"));
    }

    @Test
    @DisplayName("a CTE that reads an allow-listed table is accepted")
    void acceptsCte() {
        String sql = """
                WITH late AS (
                    SELECT t.vendor FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.delay_minutes > 10
                )
                SELECT vendor, count(*) FROM late GROUP BY vendor""";
        assertNull(SafeSqlExecutor.validate(sql));
    }

    @Test
    @DisplayName("markdown fences and trailing semicolons are stripped before validation")
    void stripsFences() {
        String fenced = "```sql\n" + OK_SQL + ";\n```";
        String stripped = SafeSqlExecutor.strip(fenced);
        assertFalse(stripped.contains("```"));
        assertFalse(stripped.endsWith(";"));
        assertNull(SafeSqlExecutor.validate(stripped));
    }
}
