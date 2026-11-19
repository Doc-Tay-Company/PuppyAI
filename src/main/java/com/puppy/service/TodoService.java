package com.puppy.service;

import com.puppy.model.TodoTask;
import com.puppy.repository.TodoRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns and persists the standalone to-do list. Calls belong on a worker thread. */
public final class TodoService {
    private final TodoRepository repository;
    private List<TodoTask> tasks;

    public TodoService(TodoRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tasks = repository.load();
    }

    public synchronized List<TodoTask> tasks() {
        return List.copyOf(tasks);
    }

    public synchronized void save(TodoTask task) {
        Objects.requireNonNull(task, "task");
        List<TodoTask> updated = new ArrayList<>(tasks);
        int index = indexOf(task.id());
        if (index >= 0) {
            updated.set(index, task);
        } else {
            updated.add(task);
        }
        repository.save(updated);
        tasks = List.copyOf(updated);
    }

    public synchronized void delete(String id) {
        List<TodoTask> updated = tasks.stream()
                .filter(task -> !task.id().equals(id))
                .toList();
        repository.save(updated);
        tasks = updated;
    }

    private int indexOf(String id) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
