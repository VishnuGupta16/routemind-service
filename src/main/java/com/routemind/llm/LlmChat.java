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

    public LlmChat(@Value("${routemind.narrative.sarvam.enabled:false}") boolean enabled,
                   @Value("${routemind.narrative.sarvam.api-key:}") String apiKey,
                   @Value("${routemind.narrative.sarvam.model:sarvam-105b}") String model,
                   @Value("${routemind.narrative.sarvam.base-url:https://api.sarvam.ai/v1/chat/completions}")
                   String baseUrl) {
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
        if (!available()) return Optional.empty();
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    "max_tokens", maxTokens,
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

            return Optional.ofNullable(content(resp)).filter(s -> !s.isBlank()).map(String::trim);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<String> ask(String system, String user) { return ask(system, user, 300); }

    /** choices[0].message.content, defensively (no direct Jackson dependency). */
    private static String content(Map<?, ?> resp) {
        if (resp == null) return null;
        if (!(resp.get("choices") instanceof List<?> l) || l.isEmpty()) return null;
        if (!(l.get(0) instanceof Map<?, ?> first)) return null;
        if (!(first.get("message") instanceof Map<?, ?> m)) return null;
        Object c = m.get("content");
        return c == null ? null : String.valueOf(c);
    }
}
