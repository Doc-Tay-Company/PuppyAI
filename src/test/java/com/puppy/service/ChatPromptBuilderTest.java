package com.puppy.service;

import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPromptBuilderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void includesDocumentTextAndMetadata() {
        DocumentAttachment document = new DocumentAttachment(
                tempDirectory.resolve("puppy-notes.pdf"),
                "Puppy facts: a puppy needs play time.",
                3,
                false);

        List<ChatMessage> prompt = ChatPromptBuilder.build(
                List.of(ChatMessage.user("Summarize this file.")), List.of(document));

        assertEquals(2, prompt.size());
        String system = prompt.get(0).content();
        assertTrue(system.contains("You are Puppy"));
        assertTrue(system.contains("<document index=\"1\""));
        assertTrue(system.contains("name=\"puppy-notes.pdf\""));
        assertTrue(system.contains("pages=\"3\""));
        assertTrue(system.contains("Puppy facts"));
        assertEquals(MessageRole.USER, prompt.get(1).role());
    }

    @Test
    void truncatesVeryLargeAttachments() {
        DocumentAttachment document = new DocumentAttachment(
                tempDirectory.resolve("huge.txt"), "a".repeat(40_000), 0, true);

        List<ChatMessage> prompt = ChatPromptBuilder.build(
                List.of(ChatMessage.user("What is in the file?")), List.of(document));

        String context = prompt.get(0).content();
        assertTrue(context.length() < 24_000,
                "Attachment context should stay within the prompt budget");
        assertTrue(context.contains("document truncated"));
    }

    @Test
    void keepsOnlyTheMostRecentHistory() {
        List<ChatMessage> conversation = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            conversation.add(new ChatMessage(
                    i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "message-" + i + "-" + "x".repeat(500)));
        }
        conversation.add(ChatMessage.user("final question"));

        List<ChatMessage> prompt = ChatPromptBuilder.build(conversation, List.of());

        assertEquals(1 + ChatPromptBuilder.MAX_HISTORY_MESSAGES, prompt.size());
        assertEquals("final question", prompt.get(prompt.size() - 1).content());
        assertEquals(1, prompt.stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .count(), "Only Puppy's own system prompt should be added");
    }

    @Test
    void skipsBlankMessagesAndSystemRolesFromTheUi() {
        List<ChatMessage> conversation = List.of(
                ChatMessage.system("do not leak this"),
                ChatMessage.user("hello"),
                new ChatMessage(MessageRole.ASSISTANT, "   "));

        List<ChatMessage> prompt = ChatPromptBuilder.build(conversation, List.of());

        assertEquals(2, prompt.size());
        assertEquals("hello", prompt.get(1).content());
    }
}
