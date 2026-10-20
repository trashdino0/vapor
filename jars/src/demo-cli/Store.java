package demo.tasks;

import java.util.List;

public interface Store {

    void add(Task task);

    List<Task> all();

    int size();
}
