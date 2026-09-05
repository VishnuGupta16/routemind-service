package com.routemind.narrative;

import com.routemind.rules.Finding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * LLM narrative via Sarvam (OpenAI-compatible chat completions).
 *
 * Cost control by design: runs ONCE per ranked finding — never per metric, never per
 * row — and {@link NarrativeService} caches the result. Any failure falls back to the
 * deterministic template, so the product degrades in wording, never in correctness.
 *
 * Note: response parsing goes through Spring's message converters (Map), deliberately
 * avoiding a direct Jackson import — Spring Boot 4 moved to Jackson 3 (tools.jackson),
 * so this stays version-agnostic.
 *
 * Enable with: routemind.narrative.sarvam.enabled=true and an api-key.
 */
@Component
@ConditionalOnProperty(prefix = "routemind.narrative.sarvam", name = "enabled",
        havingValue = "true")
public class SarvamNarrativeGenerator implements NarrativeGenerator {

    private static final String URL = "https://api.sarvam.ai/v1/chat/completions";

    /** Headroom for a reasoning model's chain of thought (see LlmChat). */
    private static final int REASONING_HEADROOM = 8000;

    private final RestClient http;
    private final String apiKey;
    private final String model;
    private final TemplateNarrativeGenerator fallback;

    public SarvamNarrativeGenerator(
            @Value("${routemind.narrative.sarvam.api-key:}") String apiKey,
            @Value("${routemind.narrative.sarvam.model:sarvam-105b}") String model,
            TemplateNarrativeGenerator fallback) {
        this.apiKey = apiKey;
        this.model = model;
        this.fallback = fallback;
        this.http = RestClient.builder().baseUrl(URL).build();
    }

    @Override
    public boolean available() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public int priority() { return 10; }

    @Override
    public String narrate(Finding f, String persona) {
        if (!available()) return fallback.narrate(f, persona);
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    // sarvam-105b is a reasoning model — its chain of thought is billed
                    // against max_tokens. 220 was consumed by the thinking alone, so the
                    // reply came back content=null and every narrative silently fell back
                    // to the template. Budget for the reasoning as well as the answer.
                    "max_tokens", 220 + REASONING_HEADROOM,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM),
                            Map.of("role", "user", "content", prompt(f, persona))));

            Map<?, ?> resp = http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("api-subscription-key", apiKey)   // Sarvam also accepts this
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            String text = extractContent(resp);
            return (text == null || text.isBlank()) ? fallback.narrate(f, persona) : text.trim();
        } catch (Exception e) {
            return fallback.narrate(f, persona);
        }
    }

    /** choices[0].message.content, defensively. */
    @SuppressWarnings("unchecked")
    private static String extractContent(Map<?, ?> resp) {
        if (resp == null) return null;
        Object choices = resp.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> first)) return null;
        Object message = first.get("message");
        if (!(message instanceof Map<?, ?> m)) return null;
        // ONLY content — never reasoning_content, which is the model's raw chain of
        // thought and must not be shown to a user. Empty content falls back to the template.
        Object content = m.get("content");
        return content == null ? null : String.valueOf(content);
    }

    private static final String SYSTEM = """
            You write short operational briefings for enterprise employee-transport managers.
            RULES:
            - Use ONLY the numbers given to you. Never invent, round differently, or add figures.
            - 2 to 3 sentences. No preamble, no bullet points, no markdown.
            - Always state the metric, its reference point, who is responsible, and the action.
            """;

    private String prompt(Finding f, String persona) {
        StringBuilder sb = new StringBuilder();
        sb.append("Audience: ").append(audience(persona)).append('\n');
        sb.append("Metric: ").append(f.displayName()).append('\n');
        sb.append("Value: ").append(f.value()).append('\n');
        sb.append("Target: ").append(f.target()).append('\n');
        if (f.priorValue() != null) sb.append("Previous period: ").append(f.priorValue()).append('\n');
        sb.append("Status: ").append(f.status()).append(" (").append(f.reason()).append(")\n");
        sb.append("Period: ").append(f.from()).append(" to ").append(f.to()).append('\n');
        if (f.businessUnit() != null) sb.append("Business unit: ").append(f.businessUnit()).append('\n');
        if (f.attribution() != null && !f.attribution().isEmpty()) {
            sb.append("Responsible (by ").append(f.attributionDimension()).append("): ");
            f.attribution().forEach(c ->
                    sb.append(c.member()).append(' ').append(c.pct()).append("%, "));
            sb.append('\n');
        }
        if (f.projection() != null && f.projection().projectedBreachDate() != null) {
            sb.append("Projected breach date: ").append(f.projection().projectedBreachDate()).append('\n');
        }
        String action = TemplateNarrativeGenerator.action(f);
        if (action != null) sb.append("Suggested action: ").append(action).append('\n');
        return sb.toString();
    }

    private String audience(String persona) {
        return switch (persona) {
            case "FACILITIES_HEAD" -> "a transport & facilities head; strategic, budget and "
                    + "SLA framing, suitable to forward to leadership unchanged";
            case "LINE_MANAGER" -> "a team/line manager; shift-level and practical — who was "
                    + "affected and what it means for floor readiness";
            default -> "a transport operations manager; direct and immediately actionable";
        };
    }
}
