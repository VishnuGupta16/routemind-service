package com.routemind.narrative;

import com.routemind.rules.Finding;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the best available generator and caches by (finding, persona).
 *
 * The cache is the cost story: a report viewed ten times costs one inference,
 * and re-running the same scan costs nothing.
 */
@Service
public class NarrativeService {

    private final List<NarrativeGenerator> generators;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public NarrativeService(List<NarrativeGenerator> generators) {
        this.generators = generators.stream()
                .sorted(Comparator.comparingInt(NarrativeGenerator::priority).reversed())
                .toList();
    }

    public Finding narrate(Finding f, String persona) {
        String key = f.dedupeKey() + "|" + f.from() + "|" + f.to() + "|" + persona;
        String text = cache.computeIfAbsent(key, k -> pick().narrate(f, persona));
        return f.withNarrative(text);
    }

    public List<Finding> narrateAll(List<Finding> findings, String persona) {
        return findings.stream().map(f -> narrate(f, persona)).toList();
    }

    private NarrativeGenerator pick() {
        return generators.stream()
                .filter(NarrativeGenerator::available)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no narrative generator"));
    }

    /** Exposed for the health endpoint — tells you if you're on the LLM or templates. */
    public String activeGenerator() {
        return pick().getClass().getSimpleName();
    }

    public int cacheSize() { return cache.size(); }
}
