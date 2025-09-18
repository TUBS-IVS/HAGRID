package hagrid.utils.routing;

import java.util.concurrent.Semaphore;

/**
 * Wraps a JspritCarrierTask and ensures that tasks exceeding a size threshold
 * can run with limited concurrency, while preserving the scheduling priority
 * of the underlying task.
 */
public class GatedCarrierTask implements PrioritizedRunnable {
    private final JspritCarrierTask delegate;
    private final Semaphore gate;
    private final int bigThreshold;
    private final int priority;

    public GatedCarrierTask(JspritCarrierTask delegate, Semaphore gate, int bigThreshold) {
        this.delegate = delegate;
        this.gate = gate;
        this.bigThreshold = bigThreshold;
        this.priority = delegate.getPriority();
    }

    @Override
    public void run() {
        boolean acquired = false;
        try {
            if (priority >= bigThreshold) {
                gate.acquire();
                acquired = true;
            }
            delegate.run();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    @Override
    public int getPriority() { return priority; }
}
