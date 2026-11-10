package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class JspritInitialTaskTest {
    @Test void anImpossibleInitialTaskCannotPublishAnEmptySuccessPlan() {
        try(var f = new OptimizationFixture()) {
            f.service("too-large",11,0,500);
            var task = new JspritCarrierTask(f.carrier,f.costs,new AtomicInteger(),1,f.costs.getNetwork());
            assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
            assertThat(f.carrier.getSelectedPlan()).isNull();
        }
    }
}
