package com.puppy.service;

import com.puppy.database.Database;
import com.puppy.model.TodoTask;
import com.puppy.repository.SettingsRepository;
import com.puppy.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoServiceTest {
    @TempDir Path directory;

    @Test
    void savesUpdatesDeletesAndRestoresTasks() {
        try (Database database = Database.open(directory)) {
            TodoRepository repository = new TodoRepository(new SettingsRepository(database));
            TodoService service = new TodoService(repository);
            TodoTask task = TodoTask.draft("Study Java");

            service.save(task);
            service.save(task.withCompleted(true));

            TodoService restored = new TodoService(repository);
            assertEquals(1, restored.tasks().size());
            assertTrue(restored.tasks().get(0).completed());

            restored.delete(task.id());
            assertTrue(new TodoService(repository).tasks().isEmpty());
        }
    }
}
