package com.routemind.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persona classification via the keyword floor — the path that runs with no API key.
 *
 * The chatbot must frame its answer for the right person even offline, because a correct set
 * of numbers written for the wrong persona is a wrong answer. These pin the deterministic
 * fallback so the demo classifies sensibly whether or not Sarvam is reachable.
 */
class PersonaClassifierTest {

    /** A model client that is always unavailable, forcing the keyword path. */
    static PersonaClassifier offline() {
        ChatModelClient noModel = new ChatModelClient(false, "", "", "");
        return new PersonaClassifier(noModel);
    }

    @Test
    @DisplayName("Budget and contract questions route to the facilities head")
    void facilitiesHead() {
        PersonaClassifier c = offline();
        assertEquals("FACILITIES_HEAD",
                c.classify("Are we over budget this month and should we renegotiate the "
                        + "vendor contract?").personaCode());
        assertEquals("FACILITIES_HEAD",
                c.classify("What is our penalty exposure across vendors this quarter?")
                        .personaCode());
    }

    @Test
    @DisplayName("\"Why is X down today\" routes to the transport manager")
    void transportManager() {
        PersonaClassifier c = offline();
        assertEquals("TRANSPORT_MANAGER",
                c.classify("Why is OTA down today and which vendor should I chase?")
                        .personaCode());
        assertEquals("TRANSPORT_MANAGER",
                c.classify("Which routes are degrading right now?").personaCode());
    }

    @Test
    @DisplayName("Team-level questions route to the line manager")
    void lineManager() {
        PersonaClassifier c = offline();
        assertEquals("LINE_MANAGER",
                c.classify("Did everyone on my team get picked up this morning?")
                        .personaCode());
        assertEquals("LINE_MANAGER",
                c.classify("Who on my shift was late for our pickups?").personaCode());
    }

    @Test
    @DisplayName("An ambiguous question defaults to the operational persona, and says so")
    void defaultsToOperational() {
        PersonaClassifier.Result r = offline().classify("Tell me about the trips.");
        assertEquals("TRANSPORT_MANAGER", r.personaCode());
        assertEquals("keyword", r.source());
        assertTrue(r.rationale().toLowerCase().contains("default"));
    }

    @Test
    @DisplayName("The classification always names its source and reasoning")
    void alwaysExplainsItself() {
        PersonaClassifier.Result r = offline().classify("why is cost per trip rising?");
        assertNotNull(r.personaCode());
        assertEquals("keyword", r.source(), "no model configured in this test");
        assertNotNull(r.rationale());
    }
}
