package com.routemind.schema;

import com.routemind.llm.LlmChat;
import com.routemind.schema.SchemaChange.Profile;
import com.routemind.schema.SchemaChange.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema evolution: detect a new column, propose, let a human decide, apply safely.
 *
 * Why this is worth testing hard: this is the one place in the system that issues DDL
 * against a database holding 615,546 trips. A mistake here does not produce a wrong
 * chart — it alters or corrupts the store the whole product reads from. The two things
 * that must hold are that the DDL can never rewrite history, and that a column's data
 * is never averaged over the months before it existed.
 */
class SchemaRegistryTest {

    /** Captures DDL instead of running it, so we can assert exactly what would be executed. */
    static class CapturingJdbc extends JdbcTemplate {
        final List<String> executed = new ArrayList<>();
        @Override public void execute(String sql) { executed.add(sql); }
    }

    /** No key configured -> available() is false -> the deterministic heuristic is used. */
    static SchemaAdvisor offlineAdvisor() {
        return new SchemaAdvisor(new LlmChat(false, "", "m", "http://localhost/none"));
    }

    static Profile profile(String type, double fill, long distinct, String... samples) {
        return new Profile(type, fill, distinct, List.of(samples));
    }

    static SchemaRegistry registry(CapturingJdbc jdbc) {
        return new SchemaRegistry(offlineAdvisor(), jdbc);
    }

    // ------------------------------------------------------------------ DDL

    @Nested
    @DisplayName("Generated DDL is backward compatible by construction")
    class Ddl {

        final SchemaRegistry reg = registry(new CapturingJdbc());

        @Test
        void addsNullableColumnWithNoDefaultAndNoBackfill() {
            String ddl = reg.ddlFor("trips", "co2_grams", profile("NUMBER", 100, 900));

            assertTrue(ddl.startsWith("ALTER TABLE trips ADD COLUMN IF NOT EXISTS"), ddl);
            assertFalse(ddl.contains("NOT NULL"), "a NOT NULL column would reject every historical row");
            assertFalse(ddl.contains("DEFAULT"), "a default would invent data for months that had none");
            assertFalse(ddl.toUpperCase().contains("UPDATE"), "adoption must never backfill");
        }

        @Test
        void neverDropsOrRetypesAnything() {
            String ddl = reg.ddlFor("billing", "surcharge", profile("NUMBER", 80, 40));
            String upper = ddl.toUpperCase();
            assertFalse(upper.contains("DROP"));
            assertFalse(upper.contains("ALTER COLUMN"));
            assertFalse(upper.contains("TYPE "));
        }

        @Test
        void isIdempotentSoRerunningAMonthIsHarmless() {
            assertTrue(reg.ddlFor("alerts", "sensor_id", profile("TEXT", 50, 12))
                    .contains("IF NOT EXISTS"));
        }

        @Test
        void mapsInferredTypesToSafePostgresTypes() {
            assertTrue(reg.ddlFor("trips", "a", profile("INTEGER", 100, 5)).endsWith("BIGINT"));
            assertTrue(reg.ddlFor("trips", "b", profile("NUMBER", 100, 5)).endsWith("NUMERIC(14,3)"));
            assertTrue(reg.ddlFor("trips", "c", profile("BOOLEAN", 100, 2)).endsWith("BOOLEAN"));
            assertTrue(reg.ddlFor("trips", "d", profile("TIMESTAMP", 100, 5)).endsWith("TIMESTAMPTZ"));
            assertTrue(reg.ddlFor("trips", "e", profile("DATE", 100, 5)).endsWith("DATE"));
        }

        @Test
        void unknownTypeFallsBackToTextRatherThanGuessing() {
            // TEXT can hold anything; a wrong numeric guess would fail the whole load
            assertTrue(reg.ddlFor("trips", "mystery", profile("SOMETHING_ODD", 100, 5))
                    .endsWith("TEXT"));
            assertTrue(reg.ddlFor("trips", "mystery2", profile(null, 100, 5)).endsWith("TEXT"));
        }

        @Test
        void columnNameFromTheCsvHeaderIsReducedToASafeIdentifier() {
            // the header is untrusted input — it must not be able to carry SQL through
            String ddl = reg.ddlFor("trips", "Trip Cost (INR); DROP TABLE trips--",
                    profile("NUMBER", 100, 9));
            String col = columnOf(ddl);

            assertFalse(ddl.contains(";"), "a semicolon would end the statement: " + ddl);
            assertFalse(ddl.contains(" DROP "), ddl);
            assertTrue(col.matches("[a-z_][a-z0-9_]*"),
                    "only word characters may reach the DDL, got: " + col);
            assertTrue(col.startsWith("trip_cost__inr"), col);
        }

        @Test
        void leadingDigitIsPrefixedBecausePostgresRejectsIt() {
            String col = columnOf(reg.ddlFor("trips", "2026_pilot_flag", profile("BOOLEAN", 100, 2)));
            assertFalse(Character.isDigit(col.charAt(0)),
                    "unquoted identifiers cannot start with a digit: " + col);
        }

        /** Pulls just the column identifier out of the generated ALTER statement. */
        String columnOf(String ddl) {
            return ddl.substring(ddl.indexOf("IF NOT EXISTS ") + "IF NOT EXISTS ".length())
                    .trim().split("\\s+")[0];
        }
    }

    // ----------------------------------------------------------- detection

    @Nested
    @DisplayName("Detection is idempotent — a re-run must not undo a human decision")
    class Detection {

        @Test
        void secondSightingOfTheSameColumnDoesNotCreateADuplicate() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            Profile p = profile("NUMBER", 100, 900);
            reg.recordNewColumn("trips", "co2_grams", "Aug.csv", LocalDate.of(2026, 8, 1), p);
            reg.recordNewColumn("trips", "co2_grams", "Sep.csv", LocalDate.of(2026, 9, 1), p);

            assertEquals(1, reg.all().size());
        }

        @Test
        void reprocessingAMonthDoesNotResetADecisionToPending() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            Profile p = profile("NUMBER", 100, 900);
            SchemaChange c = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), p);
            reg.ignore(c.id(), "vishnu", "not needed yet");

            // the same drop is loaded again — the earlier "ignore" must survive
            reg.recordNewColumn("trips", "co2_grams", "Aug.csv", LocalDate.of(2026, 8, 1), p);

            assertEquals(State.IGNORED, reg.all().get(0).state());
            assertTrue(reg.pending().isEmpty(), "a settled column must stop nagging the operator");
        }

        @Test
        void aNewColumnStartsPendingAndCarriesAProposalAndItsDdl() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange c = reg.recordNewColumn("trips", "driver_rating", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 95, 5, "4.5", "5"));

            assertTrue(c.isPending(), "nothing is adopted without a human");
            assertNotNull(c.proposal());
            assertTrue(c.migrationSql().contains("ADD COLUMN IF NOT EXISTS"));
            assertEquals(LocalDate.of(2026, 8, 1), c.availableFrom());
        }

        @Test
        void aNewCategoryValueNeedsNoDdlBecauseCategoriesAreText() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange c = reg.recordNewEnumValue("trips", "delay_reason", "Aug.csv",
                    List.of("WEATHER"));

            assertEquals(SchemaChange.Type.NEW_ENUM_VALUE, c.type());
            assertFalse(c.migrationSql().toUpperCase().contains("ALTER TABLE"));
            assertTrue(c.proposal().contains("WEATHER"));
        }
    }

    // ------------------------------------------------------------ decisions

    @Nested
    @DisplayName("Decisions — only ADOPT touches the database")
    class Decisions {

        @Test
        void adoptRunsExactlyTheProposedDdlAndNothingElse() {
            CapturingJdbc jdbc = new CapturingJdbc();
            SchemaRegistry reg = registry(jdbc);
            SchemaChange c = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 100, 900));

            SchemaChange after = reg.adopt(c.id(), "vishnu", true).orElseThrow();

            assertEquals(1, jdbc.executed.size(), "one statement, no backfill pass");
            assertEquals(c.migrationSql(), jdbc.executed.get(0),
                    "what the operator approved must be exactly what runs");
            assertEquals(State.ADOPTED, after.state());
            assertEquals("vishnu", after.decidedBy());
            assertNotNull(after.decidedAt());
        }

        @Test
        void adoptWithoutApplyRecordsTheDecisionButRunsNoDdl() {
            CapturingJdbc jdbc = new CapturingJdbc();
            SchemaRegistry reg = registry(jdbc);
            SchemaChange c = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 100, 900));

            assertEquals(State.ADOPTED, reg.adopt(c.id(), "vishnu", false).orElseThrow().state());
            assertTrue(jdbc.executed.isEmpty());
        }

        @Test
        void ignoreAndRejectNeverTouchTheDatabase() {
            CapturingJdbc jdbc = new CapturingJdbc();
            SchemaRegistry reg = registry(jdbc);
            SchemaChange a = reg.recordNewColumn("trips", "x", "f", LocalDate.now(),
                    profile("TEXT", 10, 3));
            SchemaChange b = reg.recordNewColumn("billing", "y", "f", LocalDate.now(),
                    profile("TEXT", 10, 3));

            reg.ignore(a.id(), "vishnu", null);
            reg.reject(b.id(), "vishnu", null);

            assertTrue(jdbc.executed.isEmpty());
            assertEquals(State.IGNORED, reg.all().stream()
                    .filter(c -> c.column().equals("x")).findFirst().orElseThrow().state());
            assertEquals(State.REJECTED, reg.all().stream()
                    .filter(c -> c.column().equals("y")).findFirst().orElseThrow().state());
        }

        @Test
        void aFailedDdlIsRecordedRatherThanSwallowed() {
            SchemaRegistry reg = new SchemaRegistry(offlineAdvisor(), new JdbcTemplate() {
                @Override public void execute(String sql) {
                    throw new IllegalStateException("permission denied for table trips");
                }
            });
            SchemaChange c = reg.recordNewColumn("trips", "z", "f", LocalDate.now(),
                    profile("TEXT", 10, 3));

            SchemaChange after = reg.adopt(c.id(), "vishnu", true).orElseThrow();

            assertEquals(State.ADOPTED, after.state());
            assertTrue(after.note().contains("permission denied"),
                    "the operator must be told the column was NOT actually added: " + after.note());
            assertTrue(after.note().toLowerCase().contains("manually"));
        }

        @Test
        void decidingOnAnUnknownIdReturnsEmptyRatherThanThrowing() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            assertTrue(reg.adopt("NEW_COLUMN:trips:nope", "vishnu", true).isEmpty());
            assertTrue(reg.ignore("nope", "vishnu", null).isEmpty());
            assertTrue(reg.reject("nope", "vishnu", null).isEmpty());
        }
    }

    // -------------------------------------------------- availableFrom guard

    @Nested
    @DisplayName("availableFrom — the guard against averaging over months that had no data")
    class AvailableFrom {

        @Test
        void isNotOfferedWhileTheColumnIsStillPending() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            reg.recordNewColumn("trips", "co2_grams", "Aug.csv", LocalDate.of(2026, 8, 1),
                    profile("NUMBER", 100, 900));

            assertTrue(reg.availableFrom("trips", "co2_grams").isEmpty(),
                    "nothing may compute over a column no human has approved");
        }

        @Test
        void isTheFirstDateTheColumnHasDataOnceAdopted() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange c = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 100, 900));
            reg.adopt(c.id(), "vishnu", true);

            // May-July 2026 predate the column; a metric must not reach back into them
            assertEquals(LocalDate.of(2026, 8, 1),
                    reg.availableFrom("trips", "co2_grams").orElseThrow());
        }

        @Test
        void isNotOfferedForAnIgnoredColumn() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange c = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 100, 900));
            reg.ignore(c.id(), "vishnu", "not needed");

            assertTrue(reg.availableFrom("trips", "co2_grams").isEmpty());
        }

        @Test
        void unknownColumnIsEmptyNotNull() {
            assertTrue(registry(new CapturingJdbc()).availableFrom("trips", "never_seen").isEmpty());
        }
    }

    // -------------------------------------------------------- ETL handshake

    @Nested
    @DisplayName("decisions() — the overlay validate.py reads so a settled column stops warning")
    class DecisionOverlay {

        @Test
        void containsOnlyDecidedColumns() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange decided = reg.recordNewColumn("trips", "co2_grams", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 100, 900));
            reg.recordNewColumn("trips", "still_pending", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("TEXT", 100, 3));
            reg.adopt(decided.id(), "vishnu", true);

            @SuppressWarnings("unchecked")
            Map<String, Object> trips = (Map<String, Object>) reg.decisions().get("trips");
            assertTrue(trips.containsKey("co2_grams"));
            assertFalse(trips.containsKey("still_pending"),
                    "an undecided column must keep warning on every future drop");
        }

        @Test
        void carriesTheStateAndTheDateTheEtlNeeds() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            SchemaChange c = reg.recordNewColumn("billing", "surcharge", "Aug.csv",
                    LocalDate.of(2026, 8, 1), profile("NUMBER", 60, 40));
            reg.adopt(c.id(), "vishnu", true);

            @SuppressWarnings("unchecked")
            Map<String, Object> billing = (Map<String, Object>) reg.decisions().get("billing");
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) billing.get("surcharge");

            assertEquals("ADOPTED", entry.get("state"));
            assertEquals("2026-08-01", entry.get("availableFrom"));
            assertEquals("vishnu", entry.get("decidedBy"));
        }

        @Test
        void summaryReportsCountsAndWhichAdvisorProducedTheProposals() {
            SchemaRegistry reg = registry(new CapturingJdbc());
            reg.recordNewColumn("trips", "a", "f", LocalDate.now(), profile("TEXT", 10, 3));

            Map<String, Object> s = reg.summary();
            assertEquals(1, ((Number) s.get("total")).intValue());
            assertEquals("heuristic", s.get("advisor"), "no API key configured in tests");
            @SuppressWarnings("unchecked")
            Map<String, Long> byState = (Map<String, Long>) s.get("byState");
            assertEquals(1L, byState.get("PENDING").longValue());
            assertEquals(0L, byState.get("ADOPTED").longValue());
        }
    }
}
