package com.routemind.schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A difference between what a monthly drop contained and what the contract expected.
 *
 * The important field is {@link #availableFrom()}. A newly-added column only has data
 * from the month it appeared — historical rows are NULL, meaning "not collected then",
 * NOT "zero". Any metric built on it must scope to `>= availableFrom`, otherwise it
 * silently averages over a period where the column did not exist and reports a wrong
 * number. Recording that date is what makes adoption backward compatible.
 */
public record SchemaChange(
        String id,
        Type type,
        String source,            // trips | trip_employees | billing | alerts | feedback
        String column,
        String detectedIn,        // the file it first appeared in
        LocalDate availableFrom,  // first date this column has data for
        Profile profile,
        String proposal,          // what the system recommends and why
        String migrationSql,      // the exact, backward-compatible DDL
        State state,
        Instant detectedAt,
        Instant decidedAt,
        String decidedBy,
        String note) {

    public enum Type { NEW_COLUMN, NEW_ENUM_VALUE, MISSING_COLUMN, TYPE_DRIFT }

    public enum State {
        /** waiting for a human */
        PENDING,
        /** ingest it: column added (nullable), contract updated, metrics may use it after availableFrom */
        ADOPTED,
        /** known but deliberately unused — stop warning about it */
        IGNORED,
        /** treat its presence as an error in future drops */
        REJECTED
    }

    /** What we observed about the new column, so the proposal isn't a guess. */
    public record Profile(String inferredType,
                          double nonNullPct,
                          long distinctCount,
                          List<String> sampleValues) {}

    public SchemaChange decide(State newState, String by, String note) {
        return new SchemaChange(id, type, source, column, detectedIn, availableFrom,
                profile, proposal, migrationSql, newState, detectedAt,
                Instant.now(), by, note);
    }

    public boolean isPending() { return state == State.PENDING; }
}
