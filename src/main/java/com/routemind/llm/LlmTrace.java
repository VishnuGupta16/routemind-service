package com.routemind.llm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * An audit trail of every outbound model call.
 *
 * Two reasons this exists rather than a log line:
 *
 *   1. COST. Each call is billed, and a reasoning model bills its own thinking. Knowing how
 *      many calls one question cost — and how many tokens went to reasoning rather than to
 *      the answer — is the difference between a predictable bill and a surprise.
 *   2. TRUST. The product's central claim is that the model never invents a number. That is
 *      only checkable if you can see exactly what it was asked and what came back.
 *
 * Calls are recorded per request (so an answer can carry its own trace) and into a bounded
 * ring buffer (so /api/llm/trace can show recent activity without growing without limit).
 */
@Component
public class LlmTrace {

    /** One outbound call. `answer` is truncated — this is an audit trail, not a transcript. */
    public record Call(Instant at,
                       String purpose,       // why the call was made
                       String model,
                       long millis,
                       boolean ok,
                       String outcome,       // OK | EMPTY_CONTENT | TRUNCATED | ERROR | SKIPPED
                       Integer promptTokens,
                       Integer completionTokens,
                       String detail) {}

    private static final int RING = 200;

    private final Deque<Call> recent = new ArrayDeque<>();

    /** Per-request trace, so one answer can report exactly the calls it caused. */
    private static final ThreadLocal<List<Call>> CURRENT = new ThreadLocal<>();

    /** Start collecting for this request. Safe to call when one is already open. */
    public void begin() { CURRENT.set(new ArrayList<>()); }

    /** Finish and hand back what was collected. Always clears, even on an exception path. */
    public List<Call> end() {
        List<Call> calls = CURRENT.get();
        CURRENT.remove();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    public void record(Call c) {
        List<Call> cur = CURRENT.get();
        if (cur != null) cur.add(c);
        synchronized (recent) {
            recent.addLast(c);
            while (recent.size() > RING) recent.removeFirst();
        }
    }

    /** Convenience for the common shapes. */
    public void ok(String purpose, String model, Duration took,
                   Integer promptTokens, Integer completionTokens) {
        record(new Call(Instant.now(), purpose, model, took.toMillis(), true, "OK",
                promptTokens, completionTokens, null));
    }

    public void failed(String purpose, String model, Duration took,
                       String outcome, String detail) {
        record(new Call(Instant.now(), purpose, model, took.toMillis(), false, outcome,
                null, null, detail));
    }

    public List<Call> recent(int limit) {
        synchronized (recent) {
            return recent.stream().skip(Math.max(0, recent.size() - limit)).toList();
        }
    }

    /** Rolled up for the status endpoint — the numbers an operator actually watches. */
    public Map<String, Object> summary() {
        List<Call> all = recent(RING);
        long ok = all.stream().filter(Call::ok).count();
        long tokens = all.stream()
                .filter(c -> c.completionTokens() != null)
                .mapToLong(Call::completionTokens).sum();
        double avgMs = all.stream().mapToLong(Call::millis).average().orElse(0);
        return Map.of(
                "calls", all.size(),
                "succeeded", ok,
                "failed", all.size() - ok,
                "completionTokens", tokens,
                "avgLatencyMs", Math.round(avgMs));
    }
}
