package hagrid.utils.routing;

/**
 * Enum to define the type of threading to be used for parallel processing.
 */
public enum ThreadingType {
    FORK_JOIN_POOL, MAT_SIM_THREAD_POOL, COMPLETABLE_FUTURE, SINGLE_THREAD, REACTOR // Add new threading types here
}