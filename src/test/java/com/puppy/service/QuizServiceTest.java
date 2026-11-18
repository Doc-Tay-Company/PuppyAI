package com.puppy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.puppy.ai.AiException;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.QuizDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QuizServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Fake implements QuizService.JsonChat {
        final ArrayDeque<String> replies = new ArrayDeque<>();
        final List<List<ChatMessage>> calls = new ArrayList<>();

        Fake(String... values) { replies.addAll(List.of(values)); }

        @Override
        public String call(List<ChatMessage> messages) {
            calls.add(messages);
            return replies.remove();
        }
    }

    private static ObjectNode definition(List<String> difficulties) {
        ObjectNode result = JSON.createObjectNode().put("title", "Biology");
        var questions = result.putArray("questions");
        for (int i = 0; i < difficulties.size(); i++) {
            ObjectNode question = questions.addObject().put("text", "Question " + (i + 1));
            question.putArray("options").add("One").add("Two").add("Three").add("Four");
            question.put("correctIndex", 0).put("explanation", "notes.pdf page 2: source explanation")
                    .put("difficulty", difficulties.get(i));
        }
        return result;
    }

    private static String spec(int count, List<String> difficulties) throws Exception {
        return "{\"count\":" + count + ",\"difficulties\":"
                + JSON.writeValueAsString(difficulties) + "}";
    }

    @Test
    void defaultsAndBoundaryCountsHaveFreshIdsAndExactlyTwoCalls() throws Exception {
        for (int count : List.of(1, 5, 20)) {
            List<String> levels = java.util.Collections.nCopies(count, "MEDIUM");
            Fake fake = new Fake(count == 5 ? "{\"count\":null,\"difficulties\":null}"
                    : spec(count, levels), definition(levels).toString());
            QuizDefinition quiz = new QuizService(fake).generate("message", "Biology", List.of());
            assertEquals("message", quiz.messageId());
            assertEquals(count, quiz.questions().size());
            assertEquals(2, fake.calls.size());
            UUID.fromString(quiz.id());
            for (var question : quiz.questions()) {
                UUID.fromString(question.id());
                assertNotEquals(quiz.id(), question.id());
                assertEquals(QuizDefinition.Difficulty.MEDIUM, question.difficulty());
            }
            assertEquals(count, quiz.questions().stream().map(QuizDefinition.Question::id).distinct().count());
        }
    }

    @Test
    void sendsNaturalLanguageRequestAndEnforcesOrderedMixedDifficulties() throws Exception {
        List<String> levels = List.of("EASY", "HARD", "MEDIUM");
        String request = "Three questions: first easy, second hard, third medium, about cells";
        Fake fake = new Fake(spec(3, levels), definition(levels).toString());
        QuizDefinition quiz = new QuizService(fake).generate("message", request, List.of());
        assertEquals(levels, quiz.questions().stream().map(q -> q.difficulty().name()).toList());
        assertTrue(fake.calls.get(0).get(1).content().contains(request));
        assertTrue(fake.calls.get(1).get(1).content().contains("[\"EASY\",\"HARD\",\"MEDIUM\"]"));
        assertTrue(fake.calls.get(0).get(0).content().contains("contradictions"));
        Fake mismatch = new Fake(spec(3, levels), definition(List.of("HARD", "EASY", "MEDIUM")).toString());
        assertThrows(QuizException.class,
                () -> new QuizService(mismatch).generate("message", request, List.of()));
    }

    @Test
    void invalidSpecificationsStopBeforeGenerationWithoutRetry() {
        for (String value : List.of("not json", "{}", "null", "[]",
                "{\"count\":0,\"difficulties\":null}",
                "{\"count\":21,\"difficulties\":null}",
                "{\"count\":1.0,\"difficulties\":null}",
                "{\"count\":\"1\",\"difficulties\":null}",
                "{\"count\":1,\"difficulties\":[\"EXTREME\"]}",
                "{\"count\":2,\"difficulties\":[\"EASY\"]}",
                "{\"count\":1,\"count\":2,\"difficulties\":null}",
                "{\"count\":1,\"difficulties\":null} {}",
                "{\"error\":\"INVALID_REQUEST\"}")) {
            Fake fake = new Fake(value);
            assertThrows(QuizException.class,
                    () -> new QuizService(fake).generate("message", "quiz", List.of()), value);
            assertEquals(1, fake.calls.size());
        }
    }

    @Test
    void rejectsMalformedWrongShapeAndInvalidQuestions() throws Exception {
        List<String> levels = List.of("MEDIUM", "MEDIUM");
        List<String> invalid = new ArrayList<>(List.of("```json\n{}\n```", "{}", "null",
                definition(List.of("MEDIUM")).toString(), "x".repeat(100_001)));
        for (int variant = 0; variant < 10; variant++) {
            ObjectNode result = definition(levels);
            ObjectNode first = (ObjectNode) result.path("questions").get(0);
            switch (variant) {
                case 0 -> result.put("title", " ");
                case 1 -> first.put("text", " ");
                case 2 -> first.put("explanation", " ");
                case 3 -> first.put("correctIndex", 4);
                case 4 -> first.put("correctIndex", "0");
                case 5 -> first.putArray("options").add("a").add("b").add("c");
                case 6 -> first.putArray("options").add("A b").add(" a  B ").add("c").add("d");
                case 7 -> first.putArray("options").add("a").add("b").add("c").add(" ");
                case 8 -> ((ObjectNode) result.path("questions").get(1)).put("text", " QUESTION  1 ");
                case 9 -> first.put("extra", true);
                default -> throw new AssertionError();
            }
            invalid.add(result.toString());
        }
        for (String payload : invalid) {
            Fake fake = new Fake(spec(2, levels), payload);
            assertThrows(QuizException.class,
                    () -> new QuizService(fake).generate("message", "two questions", List.of()));
            assertEquals(2, fake.calls.size());
        }
    }

    @Test
    void documentsAreEscapedBoundedAndRequireGrounding() throws Exception {
        String filename = "notes[untrusted].pdf";
        List<DocumentAttachment> documents = List.of(
                new DocumentAttachment(Path.of(filename), "--- Page 2 ---\n\"untrusted\" " + "a".repeat(25_000), 2, false),
                new DocumentAttachment(Path.of("other.txt"), "b".repeat(25_000), 0, false));
        Fake fake = new Fake("{\"count\":1,\"difficulties\":null}",
                definition(List.of("MEDIUM")).toString().replace("notes.pdf", "other.txt"));
        new QuizService(fake).generate("message", "", documents);
        String input = fake.calls.get(1).get(1).content();
        var sources = JSON.readTree(input.substring(input.indexOf("Untrusted documents: ") + 21));
        assertEquals(filename, sources.get(0).path("filename").asText());
        assertTrue(sources.get(0).path("data").asText().startsWith("--- Page 2 ---"));
        assertEquals(20_000, sources.get(0).path("data").asText().length()
                + sources.get(1).path("data").asText().length());
        assertTrue(sources.get(0).path("truncated").asBoolean());
        String system = fake.calls.get(1).get(0).content();
        assertTrue(system.contains("ground"));
        assertTrue(system.contains("filename and page"));
        assertTrue(system.contains("untrusted DATA"));

        Fake insufficient = new Fake("{\"count\":1,\"difficulties\":null}",
                "{\"error\":\"INSUFFICIENT_CONTEXT\"}");
        QuizException error = assertThrows(QuizException.class,
                () -> new QuizService(insufficient).generate("message", "topic", documents));
        assertTrue(error.getMessage().contains("enough context"));
        Fake uncited = new Fake("{\"count\":1,\"difficulties\":null}", definition(List.of("MEDIUM")).toString());
        assertThrows(QuizException.class,
                () -> new QuizService(uncited).generate("message", "topic", documents));
    }

    @Test
    void invalidInputsMakeNoCalls() {
        Fake fake = new Fake();
        QuizService service = new QuizService(fake);
        DocumentAttachment empty = new DocumentAttachment(Path.of("empty.txt"), " ", 0, false);
        assertThrows(QuizException.class, () -> service.generate("", "topic", List.of()));
        assertThrows(QuizException.class, () -> service.generate("message", "", List.of()));
        assertThrows(QuizException.class, () -> service.generate("message", "x".repeat(8_001), List.of()));
        assertThrows(QuizException.class, () -> service.generate("message", "topic", List.of(empty)));
        assertThrows(QuizException.class,
                () -> service.generate("message", "topic", java.util.Collections.nCopies(6, empty)));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void cancellationBetweenCallsAndProviderInterruptionRemainIdentifiable() {
        AtomicInteger calls = new AtomicInteger();
        QuizService service = new QuizService(messages -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return "{\"count\":1,\"difficulties\":null}";
        });
        try {
            assertThrows(QuizException.class, () -> service.generate("message", "topic", List.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
        QuizService interrupted = new QuizService(messages -> {
            throw new AiException(AiException.Reason.INTERRUPTED, "private provider detail");
        });
        try {
            QuizException error = assertThrows(QuizException.class,
                    () -> interrupted.generate("message", "topic", List.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(AiException.class, error.getCause());
            assertFalse(error.getMessage().contains("private provider detail"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preCancelledAndProviderFailuresNeverRetryOrLeakDetails() {
        Fake fake = new Fake();
        try {
            Thread.currentThread().interrupt();
            assertThrows(QuizException.class,
                    () -> new QuizService(fake).generate("message", "topic", List.of()));
            assertTrue(fake.calls.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        AtomicInteger calls = new AtomicInteger();
        QuizException error = assertThrows(QuizException.class,
                () -> new QuizService(messages -> {
                    calls.incrementAndGet();
                    throw new AiException(AiException.Reason.HTTP, "secret endpoint or key");
                }).generate("message", "topic", List.of()));
        assertEquals(1, calls.get());
        assertFalse(error.getMessage().contains("secret"));
    }
}
