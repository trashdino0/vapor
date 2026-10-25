package dev.vapor.testing;

/**
 * Differential fixture for record component renaming.
 *
 * <p>Uses two components with different descriptors, a custom instance method
 * that goes through the accessors, and implicit {@code equals}/{@code hashCode}/
 * {@code toString} (the {@code ObjectMethods} bootstrap). The renamer test
 * loads a transformed copy in a child loader and compares behavior.</p>
 */
public record RecordFixture(String name, int count) {

    /** Exercises accessor call-site remapping inside a normal method body. */
    public String summary() {
        return name() + "#" + count();
    }

    /** Exercises field access (constructor + method) after field renaming. */
    public RecordFixture withCount(int newCount) {
        return new RecordFixture(name(), newCount);
    }
}
