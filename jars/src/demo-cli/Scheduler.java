package demo.tasks;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class Scheduler {

    private final Store store;

    public Scheduler(Store store) {
        this.store = store;
    }

    /** Highest priority first, then alphabetical — sorting must stay stable for the demo to be comparable. */
    public List<Task> byPriority() {
        return store.all().stream()
                .sorted(Comparator.comparingInt((Task task) -> task.priority().weight()).reversed()
                        .thenComparing(Task::title))
                .collect(Collectors.toList());
    }

    /** Greedy fill: admittedly not optimal, honest about it. */
    public Plan plan(int budgetHours) {
        int chosen = 0;
        int hours = 0;
        int skipped = 0;
        for (Task task : byPriority()) {
            if (hours + task.hours() <= budgetHours) {
                chosen++;
                hours += task.hours();
            } else {
                skipped++;
            }
        }
        return new Plan(chosen, hours, skipped);
    }

    public String describe(String query) {
        try {
            Priority priority = Priority.of(query);
            long count = store.all().stream().filter(task -> task.priority() == priority).count();
            return priority.label() + ": " + count + " task(s)";
        } catch (IllegalArgumentException e) {
            return "unrecognized filter \"" + query + "\"";
        }
    }
}
