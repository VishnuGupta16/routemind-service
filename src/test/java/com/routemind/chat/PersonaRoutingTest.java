package com.routemind.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The keyword floor is what runs with no model key, so it must route these on its own.
 * Each case here was misrouted to TRANSPORT_MANAGER before the decisive-cue lists existed.
 */
class PersonaRoutingTest {

    // null model => classify() falls straight through to the keyword floor
    private String route(String q) { return new PersonaClassifier(null).classify(q).personaCode(); }

    @Test
    @DisplayName("a contract question is the facilities head's, despite naming vendors")
    void slaQuestion() {
        assertEquals("FACILITIES_HEAD",
                route("Which vendors missed the SLA they signed, and what is our penalty exposure?"));
    }

    @Test
    @DisplayName("a team question is the line manager's, despite mentioning lateness")
    void teamQuestion() {
        assertEquals("LINE_MANAGER",
                route("How many of my team were late or did not show up for the morning shift?"));
    }

    @Test
    @DisplayName("a cost question is the facilities head's")
    void costQuestion() {
        assertEquals("FACILITIES_HEAD", route("What is driving our cost per trip up?"));
    }

    @Test
    @DisplayName("an operational question stays with the transport manager")
    void operationalQuestion() {
        assertEquals("TRANSPORT_MANAGER",
                route("Why is OTA down this month and which vendor should I call first?"));
    }
}
