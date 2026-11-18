package com.puppy.service;

import com.puppy.model.StoredConversation;
import com.puppy.model.QuizDefinition;
import com.puppy.repository.ChatRepository;
import com.puppy.repository.SettingsRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns immutable history snapshots and retries failed writes in submission order. */
public final class ChatHistoryService implements AutoCloseable {
    private final ChatRepository repository;
    private final SettingsRepository settings;
    private final Map<String, StoredConversation> conversations = new LinkedHashMap<>();
    private final Map<String, StoredConversation> dirty = new LinkedHashMap<>();
    private final Map<String, QuizDefinition> quizzes = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "puppy-history");
        thread.setDaemon(true);
        return thread;
    });
    private String activeId;

    public ChatHistoryService(ChatRepository repository, SettingsRepository settings) {
        this.repository = repository;
        this.settings = settings;
        repository.load().forEach(chat -> conversations.put(chat.id(), chat));
        repository.quizzes().forEach(quiz -> quizzes.put(quiz.messageId(), quiz));
        for (StoredConversation chat : conversations.values()) {
            for (var message : chat.messages()) {
                if (message.entryType().equals("QUIZ") && !quizzes.containsKey(message.id())) {
                    throw new IllegalStateException("A saved quiz entry is missing its definition. Restore a verified backup.");
                }
            }
        }
        activeId = settings.get("activeConversation").orElse("");
    }

    public synchronized List<StoredConversation> conversations() {
        return conversations.values().stream()
                .sorted(Comparator.comparingLong(StoredConversation::updatedAt).reversed()).toList();
    }

    public synchronized String activeId() {
        return activeId;
    }

    public synchronized CompletableFuture<Void> save(StoredConversation snapshot) {
        StoredConversation old = conversations.get(snapshot.id());
        StoredConversation updated = old == null ? snapshot : new StoredConversation(snapshot.id(),
                old.title(), snapshot.updatedAt(), old.titleState(), snapshot.messages(), snapshot.attachments());
        conversations.put(snapshot.id(), updated);
        dirty.put(snapshot.id(), updated);
        return CompletableFuture.runAsync(() -> persist(snapshot.id()), writer);
    }

    private void persist(String id) {
        StoredConversation snapshot;
        synchronized (this) {
            snapshot = dirty.get(id);
        }
        if (snapshot == null) {
            return;
        }
        List<QuizDefinition> definitions;
        synchronized (this) {
            definitions = snapshot.messages().stream().filter(message -> message.entryType().equals("QUIZ"))
                    .map(message -> {
                        QuizDefinition quiz = quizzes.get(message.id());
                        if (quiz == null) {
                            throw new IllegalStateException("The quiz definition is not available for saving.");
                        }
                        return quiz;
                    }).toList();
        }
        repository.saveWithQuizzes(snapshot, definitions);
        synchronized (this) {
            dirty.remove(id, snapshot);
        }
    }

    public synchronized CompletableFuture<Void> activate(String id) {
        activeId = id;
        return CompletableFuture.runAsync(() -> settings.put("activeConversation", id), writer);
    }

    /** Registers validated content in memory; its next chat save commits both atomically. */
    public synchronized void registerQuiz(QuizDefinition quiz) {
        QuizDefinition old = quizzes.putIfAbsent(quiz.messageId(), quiz);
        if (old != null && !old.equals(quiz)) {
            throw new IllegalArgumentException("That chat entry already has a different quiz.");
        }
    }

    public synchronized java.util.Optional<QuizDefinition> quiz(String messageId) {
        return java.util.Optional.ofNullable(quizzes.get(messageId));
    }

    public synchronized CompletableFuture<Void> title(String id, String title, String state) {
        return CompletableFuture.runAsync(() -> {
            repository.setTitle(id, title, state);
            synchronized (this) {
                StoredConversation old = conversations.get(id);
                if (old != null) {
                    StoredConversation updated = new StoredConversation(id, title, old.updatedAt(), state,
                            old.messages(), old.attachments());
                    conversations.put(id, updated);
                    if (dirty.containsKey(id)) {
                        dirty.put(id, updated);
                    }
                }
            }
        }, writer);
    }

    public CompletableFuture<Void> retry() {
        return CompletableFuture.runAsync(() -> {
            List<String> ids;
            synchronized (this) {
                ids = List.copyOf(dirty.keySet());
            }
            for (String id : ids) {
                persist(id);
            }
            String active;
            synchronized (this) {
                active = activeId;
            }
            settings.put("activeConversation", active);
        }, writer);
    }

    public CompletableFuture<Void> flush() {
        return retry();
    }

    @Override
    public void close() {
        writer.shutdown();
        boolean interrupted = false;
        try {
            while (!writer.isTerminated()) {
                try {
                    writer.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
