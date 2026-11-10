package hagrid.core.routing;

import java.util.concurrent.FutureTask;

@Deprecated(forRemoval = false)
public class CustomFutureTask<T> extends FutureTask<T> implements Comparable<CustomFutureTask<T>> {
    private final PrioritizedRunnable task;

    /**
     * Constructs a new CustomFutureTask.
     *
     * @param task The runnable task.
     */
    public CustomFutureTask(Runnable task) {
        this(task,null);
    }

    public CustomFutureTask(Runnable task,T value) {
        super(task,value);
        this.task = (PrioritizedRunnable) task;
    }

    @Override
    public int compareTo(CustomFutureTask<T> that) {
        // small-first: lower service count first
        return Integer.compare(this.task.getPriority(), that.task.getPriority());
    }
}
