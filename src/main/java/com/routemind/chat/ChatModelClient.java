package com.routemind.chat;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The one place that talks to the language model, via langchain4j.
 *
 * langchain4j is the JVM port of LangChain; its OpenAI module speaks to any
 * OpenAI-compatible endpoint, which is how it reaches Sarvam — we just point the base URL
 * at Sarvam and hand it the key. Isolating the model behind this thin client means the rest
 * of the chatbot depends on one small surface, and the "what if the model is down" answer
 * lives in exactly one place: {@link #ask} returns empty, and every caller already has a
 * deterministic fallback.
 *
 * The key comes from the environment (SARVAM_API_KEY), never from a file in the repo.
 */
@Component
public class ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(ChatModelClient.class);

    private final OpenAiChatModel model;   // null when no key is configured
    private final boolean enabled;

    public ChatModelClient(
            @Value("${routemind.chat.enabled:true}") boolean enabled,
            @Value("${routemind.chat.base-url:https://api.sarvam.ai/v1}") String baseUrl,
            @Value("${routemind.chat.model:sarvam-m}") String modelName,
            @Value("${routemind.chat.api-key:}") String apiKey) {

        this.enabled = enabled && apiKey != null && !apiKey.isBlank();
        if (this.enabled) {
            this.model = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.1)          // near-deterministic; this narrates facts
                    .timeout(Duration.ofSeconds(30))
                    .build();
            log.info("QA chatbot: langchain4j -> {} ({})", baseUrl, modelName);
        } else {
            this.model = null;
            log.info("QA chatbot: no SARVAM_API_KEY set — running in deterministic mode");
        }
    }

    public boolean available() { return enabled && model != null; }

    /**
     * Ask the model. Returns empty on ANY failure — no key, network error, bad response —
     * so callers must always have a deterministic fallback. The chatbot never depends on
     * this succeeding.
     */
    public Optional<String> ask(String prompt) {
        if (!available()) return Optional.empty();
        try {
            String out = model.chat(prompt);
            return out == null || out.isBlank() ? Optional.empty() : Optional.of(out.trim());
        } catch (Exception e) {
            log.warn("chat model call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
