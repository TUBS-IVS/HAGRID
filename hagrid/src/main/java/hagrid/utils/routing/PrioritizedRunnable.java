package hagrid.utils.routing;

/**
 * A Runnable with an associated priority used for scheduling.
 */
public interface PrioritizedRunnable extends Runnable {
    /**
     * Lower values indicate higher scheduling priority when using small-first ordering.
     */
    int getPriority();
}
