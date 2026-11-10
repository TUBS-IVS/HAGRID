package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class SolverWorkersTest {
    @Test void singleThreadSearchCreatesNoAdditionalExecutor() {
        try(var workers=new SolverWorkers(1)) {assertThat(workers.executor()).isNull();}
    }

    @Test void cancellationWaitsForAnActualUninterruptibleCallbackAndPreservesTheCallerInterrupt() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);
        var exited=new AtomicBoolean();var interruptPreserved=new AtomicBoolean();
        try(var workers=new SolverWorkers(2)) {
            workers.executor().submit(()->{
                entered.countDown();
                boolean interrupted=false;
                try {
                    while(release.getCount()!=0) {
                        try {release.await();}catch(InterruptedException e){interrupted=true;}
                    }
                }finally{exited.set(true);if(interrupted)Thread.currentThread().interrupt();}
            });
            var closer=new Thread(()->{
                Thread.currentThread().interrupt();
                workers.close();
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                returned.countDown();
            },"test-owned-search-close");
            closer.setDaemon(true);
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                closer.start();
                assertThat(returned.await(100,TimeUnit.MILLISECONDS)).isFalse();
                release.countDown();
                assertThat(returned.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(exited).isTrue();assertThat(interruptPreserved).isTrue();
                assertThat(workers.executor().isTerminated()).isTrue();
            }finally{release.countDown();closer.join(3000);}
        }
    }

    @Test void rawMultithreadConstructionRequiresScopedOwnershipAndTheScopedSearchActuallyWorks() {
        var config=hagrid.core.config.HagridConfigResolver.resolve(new hagrid.core.HagridConfig(),
                hagrid.core.config.HagridConfigResolver.hannoverTarget(),
                hagrid.core.config.HagridConfigResolver.Overrides.of(Map.of("resources.internalSolverThreads","2")),Map.of());
        try(var f=new OptimizationFixture(config)) {
            f.service("a",1,0,500);f.service("b",1,0,500);
            var builder=org.matsim.freight.carriers.jsprit.MatsimJspritFactory.createRoutingProblemBuilder(f.carrier,f.costs.getNetwork());
            builder.setRoutingCost(f.costs);var problem=builder.build();
            assertThatThrownBy(()->HAGRIDRouterUtils.configureAlgorithm(problem,config,42,1,HAGRIDRouterUtils.AlgorithmPhase.REPLANNING))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withConfiguredAlgorithm");
            var solution=HAGRIDRouterUtils.withConfiguredAlgorithm(problem,config,42,1,HAGRIDRouterUtils.AlgorithmPhase.REPLANNING,
                    algorithm->com.graphhopper.jsprit.core.util.Solutions.bestOf(algorithm.searchSolutions()));
            assertThat(solution.getUnassignedJobs()).isEmpty();assertThat(solution.getCost()).isFinite();
        }
    }
}