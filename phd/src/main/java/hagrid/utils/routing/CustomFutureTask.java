package hagrid.utils.routing;

import java.util.concurrent.FutureTask;

public class CustomFutureTask<T> extends FutureTask<T> implements Comparable<CustomFutureTask<T>> {
    private final JspritCarrierTask task;

    /**
     * Constructs a new CustomFutureTask.
     *
     * @param task The runnable task.
     */
    public CustomFutureTask(Runnable task) {
        super(task, null);
        this.task = (JspritCarrierTask) task;
    }

    @Override
    public int compareTo(CustomFutureTask<T> that) {
        return that.task.getPriority() - this.task.getPriority();
    }
}
