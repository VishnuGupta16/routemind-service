package com.routemind.api;

import com.routemind.chat.QaChatbotService;
import com.routemind.chat.QaChatbotService.ChatAnswer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * The QA chatbot endpoint.
 *
 * POST a question; get back a persona-shaped answer plus the structured facts it was built
 * from. The chatbot classifies who is asking, runs the real diagnosis, and formats the
 * result — it never invents a number, so the {@code facts} on the response can always be
 * checked against the prose.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final QaChatbotService chatbot;

    public ChatController(QaChatbotService chatbot) { this.chatbot = chatbot; }

    public record ChatRequest(String question, String businessUnit,
                              LocalDate from, LocalDate to) {}

    @PostMapping
    public ChatAnswer chat(@RequestBody ChatRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        return chatbot.ask(req.question(), blankToNull(req.businessUnit()),
                req.from(), req.to());
    }

    /** GET form, handy for a quick demo from the browser or curl. */
    @GetMapping
    public ChatAnswer ask(
            @RequestParam String q,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return chatbot.ask(q, blankToNull(businessUnit), from, to);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("chatbot", "up",
                "note", "answers deterministically when no model key is set");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
