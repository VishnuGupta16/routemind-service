package com.routemind.onboarding;

import com.routemind.onboarding.SourceProfile.ColumnProfile;
import com.routemind.onboarding.SourceProfile.FieldMapping;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Maps unknown incoming columns onto our canonical fields — cheapest tier first,
 * so the LLM is only ever asked about the leftovers.
 *
 *   TIER 1  alias dictionary   — free, deterministic, resolves most real exports
 *   TIER 2  value patterns     — timestamps, epochs, lat/lon, small enum sets
 *   TIER 3  LLM proposal       — the remainder (wired via NarrativeGenerator's model)
 */
@Service
public class FieldIdentifier {

    /** canonical field -> accepted source names (lowercased, non-alphanumeric stripped). */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("tripId",        List.of("tripid", "rideid", "journeyid", "tripno")),
            Map.entry("vendor",        List.of("vendor", "vendorid", "vendorname", "supplier")),
            Map.entry("businessUnit",  List.of("businessunit", "bu", "tenant", "client")),
            Map.entry("office",        List.of("office", "site", "campus", "location")),
            Map.entry("tripDate",      List.of("tripdate", "date", "servicedate", "rundate")),
            Map.entry("shift",         List.of("shifttype", "shift", "slot")),
            Map.entry("direction",     List.of("tripdirection", "direction", "triptype", "logtype")),
            Map.entry("plannedStart",  List.of("plannedstartepoch", "plannedstart", "schedstart",
                                               "plannedpickupepoch", "scheduledpickup")),
            Map.entry("actualStart",   List.of("actualstartepoch", "actualstart",
                                               "actualpickupepoch", "actualpickup")),
            Map.entry("delayMinutes",  List.of("delayminutes", "delaymins", "latenessminutes")),
            Map.entry("cost",          List.of("tripcost", "cost", "billingamount", "amount", "fare")),
            Map.entry("employeeId",    List.of("stwid", "empid", "employeeid", "userid")),
            Map.entry("capacity",      List.of("actualcabcapacity", "capacity", "seats")),
            Map.entry("occupancy",     List.of("actualemployeecnt", "occupancy", "pax",
                                               "passengercount")),
            Map.entry("isNoShow",      List.of("isnoshow", "noshow", "noshowflag")),
            Map.entry("fuelType",      List.of("actualcabfueltype", "fueltype", "fuel")),
            Map.entry("rating",        List.of("driverrating", "rating", "score")),
            Map.entry("eventType",     List.of("eventtype", "alerttype", "incidenttype")),
            Map.entry("severity",      List.of("severity", "sev", "priority"))
    );

    private static final Pattern EPOCH = Pattern.compile("^\\d{9,13}(\\.\\d+)?$");
    private static final Pattern ISO_TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T].*)?$");
    private static final Pattern LONG_DATE = Pattern.compile("^[A-Z][a-z]+ \\d{1,2}, \\d{4}.*$");
    private static final Set<String> BOOLS = Set.of("true", "false", "yes", "no", "0", "1");

    public List<FieldMapping> identify(List<ColumnProfile> columns) {
        List<FieldMapping> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();

        // ---- tier 1: alias dictionary
        for (ColumnProfile c : columns) {
            String norm = normalise(c.name());
            for (var e : ALIASES.entrySet()) {
                if (taken.contains(e.getKey())) continue;
                if (e.getValue().contains(norm)) {
                    out.add(new FieldMapping(e.getKey(), c.name(), 0.97, "TIER1_ALIAS",
                            "exact alias match"));
                    taken.add(e.getKey());
                    break;
                }
            }
        }

        // ---- tier 2: value patterns (only for still-unmapped columns)
        Set<String> mappedCols = new HashSet<>();
        out.forEach(m -> mappedCols.add(m.sourceColumn()));
        for (ColumnProfile c : columns) {
            if (mappedCols.contains(c.name())) continue;
            String guess = byPattern(c);
            if (guess != null && !taken.contains(guess)) {
                out.add(new FieldMapping(guess, c.name(), 0.72, "TIER2_PATTERN",
                        "matched value pattern"));
                taken.add(guess);
                mappedCols.add(c.name());
            }
        }
        return out;
    }

    /** Columns still unresolved after tiers 1–2 — these are what the LLM is asked about. */
    public List<String> unresolved(List<ColumnProfile> columns, List<FieldMapping> mappings) {
        Set<String> mapped = new HashSet<>();
        mappings.forEach(m -> mapped.add(m.sourceColumn()));
        return columns.stream().map(ColumnProfile::name)
                .filter(n -> !mapped.contains(n)).toList();
    }

    private String byPattern(ColumnProfile c) {
        List<String> s = c.sampleValues() == null ? List.of() : c.sampleValues();
        if (s.isEmpty()) return null;
        boolean allEpoch = s.stream().allMatch(v -> EPOCH.matcher(clean(v)).matches());
        if (allEpoch) return c.name().toLowerCase().contains("actual") ? "actualStart" : "plannedStart";

        boolean allDate = s.stream().allMatch(v -> ISO_TS.matcher(v.trim()).matches()
                || LONG_DATE.matcher(v.trim()).matches());
        if (allDate) return "tripDate";

        boolean allBool = s.stream().allMatch(v -> BOOLS.contains(v.trim().toLowerCase()));
        if (allBool && c.name().toLowerCase().contains("show")) return "isNoShow";

        // small enum set that looks like a direction
        if (c.distinctCount() == 2 && s.stream().anyMatch(v ->
                v.equalsIgnoreCase("LOGIN") || v.equalsIgnoreCase("LOGOUT"))) return "direction";

        return null;
    }

    private static String clean(String v) { return v == null ? "" : v.replace(",", "").trim(); }

    private static String normalise(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
