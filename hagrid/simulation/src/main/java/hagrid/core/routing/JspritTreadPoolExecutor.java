package hagrid.core.routing;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Deprecated(forRemoval = false)
public class JspritTreadPoolExecutor extends ThreadPoolExecutor {

    /**
     * Constructs a new JspritTreadPoolExecutor.
     *
     * @param workQueue The work queue for the thread pool.
     * @param nThreads  The number of threads in the pool.
     */
    public JspritTreadPoolExecutor(BlockingQueue<Runnable> workQueue, int nThreads) {
        super(nThreads, nThreads, 0, TimeUnit.SECONDS, workQueue);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        if(runnable instanceof PrioritizedRunnable)return new CustomFutureTask<>(runnable,value);
        return super.newTaskFor(runnable,value);
    }
}
