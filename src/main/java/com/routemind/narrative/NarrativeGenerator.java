package com.routemind.narrative;

import com.routemind.rules.Finding;

/**
 * Turns a Finding into prose for a persona.
 *
 * Contract: the generator may only rephrase numbers already present in the
 * Finding. It never computes or invents one.
 */
public interface NarrativeGenerator {

    String narrate(Finding finding, String persona);

    /** Higher wins when several implementations are on the classpath. */
    default int priority() { return 0; }

    default boolean available() { return true; }
}
