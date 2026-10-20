package demo.tasks;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Store store = new MemoryStore();
        store.add(new Task("Write specification", Priority.HIGH, 5));
        store.add(new Task("Fix login bug", Priority.CRITICAL, 3));
        store.add(new Task("Refactor parser", Priority.NORMAL, 8));
        store.add(new Task("Update docs", Priority.LOW, 2));
        store.add(new Task("Ship release", Priority.CRITICAL, 1));
        store.add(new Task("Clean fixtures", Priority.LOW, 4));

        Scheduler scheduler = new Scheduler(store);

        System.out.println("== backlog ==");
        for (Task task : scheduler.byPriority()) {
            System.out.println(String.format("%-9s %2dh  %s",
                    task.priority().label(), task.hours(), task.title()));
        }

        System.out.println();
        System.out.println("== plan ==");
        Plan plan = scheduler.plan(16);
        System.out.println("chosen: " + plan.count());
        System.out.println("hours: " + plan.hours());
        System.out.println("skipped: " + plan.skipped());

        System.out.println();
        System.out.println("== filter ==");
        System.out.println("CRITICAL -> " + scheduler.describe("CRITICAL"));
        System.out.println("low -> " + scheduler.describe("low"));
        System.out.println("URGENT -> " + scheduler.describe("URGENT"));

        // A local-held URL is a plain LDC and must vanish from the obfuscated jar.
        String reportUrl = "https://internal.example.com/quarterly/report";
        System.out.println();
        System.out.println("report: " + reportUrl);
    }
}
