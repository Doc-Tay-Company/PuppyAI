package com.puppy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockChatResponderTest {

    private final MockChatResponder responder = new MockChatResponder();

    @Test
    void greetingIsNotBlank() {
        assertFalse(MockChatResponder.GREETING.isBlank());
    }

    @Test
    void alwaysReturnsANonBlankReply() {
        assertFalse(responder.replyTo("Hello!").isBlank());
        assertFalse(responder.replyTo("").isBlank());
        assertFalse(responder.replyTo(null).isBlank());
    }

    @Test
    void recognisesFocusRequests() {
        assertTrue(responder.replyTo("Can we focus for a bit?").contains("FOCUS"));
    }

    @Test
    void recognisesHelpRequests() {
        assertTrue(responder.replyTo("How does this work?").contains("Double-click"));
    }

    @Test
    void fallbackRepliesRotate() {
        String first = responder.replyTo("just chatting");
        String second = responder.replyTo("still chatting");

        assertNotEquals(first, second);
    }

    @Test
    void repliesAreDeterministicForFreshInstances() {
        assertEquals(new MockChatResponder().replyTo("tell me something"),
                new MockChatResponder().replyTo("tell me something"));
    }
}
