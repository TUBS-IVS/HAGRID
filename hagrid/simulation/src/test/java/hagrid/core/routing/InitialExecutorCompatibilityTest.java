package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class InitialExecutorCompatibilityTest {
    @Test void ordinaryRunnableDoesNotRequireAPriorityCast() throws Exception {
        var executor=new JspritTreadPoolExecutor(new LinkedBlockingQueue<>(),1);
        try {
            var calls=new AtomicInteger();Runnable task=()->calls.incrementAndGet();
            assertThat(executor.submit(task,"finished").get(5,TimeUnit.SECONDS)).isEqualTo("finished");
            assertThat(calls.get()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void prioritizedRunnablePreservesTheSubmittedResult() throws Exception {
        var executor=new JspritTreadPoolExecutor(new LinkedBlockingQueue<>(),1);
        try {
            var task=new PrioritizedRunnable(){public void run(){} public int getPriority(){return 1;}};
            assertThat(executor.submit(task,"finished").get(5,TimeUnit.SECONDS)).isEqualTo("finished");
        } finally { executor.shutdownNow(); }
    }
}
