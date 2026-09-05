package com.routemind.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One small, general-purpose chat client (Sarvam / any OpenAI-compatible endpoint).
 *
 * Deliberately thin: the product's numbers never come from here. It is used for the two
 * genuinely fuzzy jobs — writing a narrative from an already-computed Finding, and
 * interpreting an unfamiliar new column during onboarding. Both are once-per-event and
 * both degrade to deterministic logic when this is unavailable.
 *
 * Always present as a bean; {@link #available()} reports whether it can actually call out.
 */
@Component
public class LlmChat {

    private final RestClient http;
    private final String apiKey;
    private final String model;
    private final boolean enabled;
    private final LlmTrace trace;

    public LlmChat(@Value("${routemind.narrative.sarvam.enabled:false}") boolean enabled,
                   @Value("${routemind.narrative.sarvam.api-key:}") String apiKey,
                   @Value("${routemind.narrative.sarvam.model:sarvam-105b}") String model,
                   @Value("${routemind.narrative.sarvam.base-url:https://api.sarvam.ai/v1/chat/completions}")
                   String baseUrl,
                   LlmTrace trace) {
        this.trace = trace;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Returns empty on any failure — callers must have a deterministic fallback. */
    public Optional<String> ask(String system, String user, int maxTokens) {
        return ask(system, user, maxTokens, "unspecified");
    }

    /** Same, but records WHY the call was made so the trace is readable. */
    public Optional<String> ask(String system, String user, int maxTokens, String purpose) {
        if (!available()) {
            trace.failed(purpose, model, Duration.ZERO, "SKIPPED", "no api key or disabled");
            return Optional.empty();
        }
        long t0 = System.nanoTime();
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    // Sarvam's 105b is a REASONING model: it emits a chain of thought into
                    // reasoning_content before any answer, and that thinking is billed against
                    // max_tokens. Asking for only the answer length makes it hit the cap mid-
                    // thought, return finish_reason=length and content=null — which looked
                    // exactly like "no model configured". Budget for the reasoning too.
                    "max_tokens", maxTokens + REASONING_HEADROOM,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)));

            Map<?, ?> resp = http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("api-subscription-key", apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Duration took = Duration.ofNanos(System.nanoTime() - t0);
            Optional<String> out = Optional.ofNullable(content(resp))
                    .filter(s -> !s.isBlank()).map(String::trim);

            Integer pt = usage(resp, "prompt_tokens");
            Integer ct = usage(resp, "completion_tokens");
            if (out.isPresent()) {
                trace.ok(purpose, model, took, pt, ct);
            } else {
                // The failure mode that made the model look "not configured": the reasoning
                // budget was consumed before any answer was emitted.
                trace.failed(purpose, model, took, "EMPTY_CONTENT",
                        "finish_reason=" + finishReason(resp) + ", completion_tokens=" + ct);
            }
            return out;
        } catch (Exception e) {
            trace.failed(purpose, model, Duration.ofNanos(System.nanoTime() - t0),
                    "ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Integer usage(Map<?, ?> resp, String field) {
        if (resp == null || !(resp.get("usage") instanceof Map<?, ?> u)) return null;
        return u.get(field) instanceof Number n ? n.intValue() : null;
    }

    private static String finishReason(Map<?, ?> resp) {
        if (resp == null || !(resp.get("choices") instanceof List<?> l) || l.isEmpty()) return "?";
        return l.get(0) instanceof Map<?, ?> c ? String.valueOf(c.get("finish_reason")) : "?";
    }

    public Optional<String> ask(String system, String user) { return ask(system, user, 300); }

    /**
     * Extra token budget for a reasoning model's chain of thought, which is billed against
     * max_tokens but is not part of the answer.
     *
     * Measured against sarvam-105b: ~570 tokens of thinking to answer "say OK"; a
     * multi-dimension OTA decomposition burned past 3,600. Too small and the reply comes
     * back finish_reason=length with content=null — indistinguishable from "no model
     * configured", which is exactly the bug this replaced, so this is set generously.
     * It is a CEILING, not a cost: unused budget is not billed.
     */
    private static final int REASONING_HEADROOM = 8000;

    /** choices[0].message.content, defensively (no direct Jackson dependency). */
    private static String content(Map<?, ?> resp) {
        if (resp == null) return null;
        if (!(resp.get("choices") instanceof List<?> l) || l.isEmpty()) return null;
        if (!(l.get(0) instanceof Map<?, ?> first)) return null;
        if (!(first.get("message") instanceof Map<?, ?> m)) return null;
        // ONLY content. A reasoning model also returns reasoning_content — its raw chain
        // of thought — and that must never reach a user: it is unpolished, may contradict
        // itself mid-way, and reads as a leak. If content is empty the model ran out of
        // budget before answering, and the deterministic fallback is the better answer.
        Object c = m.get("content");
        return c == null ? null : String.valueOf(c);
    }
}
