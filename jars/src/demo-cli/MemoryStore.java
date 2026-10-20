package demo.tasks;

import java.util.ArrayList;
import java.util.List;

public final class MemoryStore implements Store {

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public void add(Task task) {
        tasks.add(task);
    }

    @Override
    public List<Task> all() {
        return List.copyOf(tasks);
    }

    @Override
    public int size() {
        return tasks.size();
    }
}
