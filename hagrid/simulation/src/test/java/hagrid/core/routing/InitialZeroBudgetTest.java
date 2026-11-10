package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.hannover.pipeline.ScenarioConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class InitialZeroBudgetTest {
 @TempDir Path directory;
 @Test void zeroFlowsThroughCentralFileAndResolvedConfigWithDefaultZero() throws Exception {
  var file=directory.resolve("initial.properties");Files.writeString(file,"optimization.initialIterations=0\n");
  var cfg=new HagridConfig();HagridConfigFile.load(file).applyTo(cfg);
  assertThat(cfg.routing().getJspritIterations()).isZero();
  var resolved=HagridConfigResolver.resolve(cfg,HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.empty(),Map.of());
  assertThat(resolved.optimization().initialIterations()).isZero();
  assertThat(new HagridConfig().routing().getJspritIterations()).isZero();
 }
 @Test void pipelineKeepsLiteralZeroAndRejectsNegativeBudget() {
  assertThat(ScenarioConfig.builder().jspritIterations(0).build().getPipelineSettings().getJspritIterations()).isZero();
  assertThatThrownBy(()->ScenarioConfig.builder().jspritIterations(-1)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new HagridConfig().routing().setJspritIterations(-1)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void servicesConstructAndValidateWithNoImprovementIteration() {try(var f=new OptimizationFixture()) {
  f.service("a",1,0,500);f.service("b",1,0,500);
  var result=new CarrierOptimizationService().optimizeInitial(f.carrier,f.costs,0,25200,0,ResolvedHagridConfig.Resources.defaults(),1337);
  assertThat(result.iterations()).isZero();assertThat(result.unassignedJobIds()).isEmpty();assertThat(result.diagnostics()).isEmpty();
  assertThat(result.plan().getScheduledTours()).isNotEmpty();assertThat(f.carrier.getSelectedPlan()).isNull();
  assertThat(PlanValidator.validate(f.carrier,result.plan(),f.request,f.config).valid()).isTrue();
 }}
 @Test void shipmentsConstructAndValidateWithNoImprovementIteration() {try(var f=new OptimizationFixture()) {
  var shipment=CarrierShipment.Builder.newInstance(Id.create("shipment",CarrierShipment.class),Id.createLinkId("depot"),Id.createLinkId("stop"),1)
   .setPickupDuration(5).setDeliveryDuration(5).setPickupStartingTimeWindow(TimeWindow.newInstance(0,500)).setDeliveryStartingTimeWindow(TimeWindow.newInstance(0,500)).build();
  f.carrier.getShipments().put(shipment.getId(),shipment);
  var result=new CarrierOptimizationService().optimizeInitial(f.carrier,f.costs,0,25200,0,ResolvedHagridConfig.Resources.defaults(),1337);
  assertThat(result.iterations()).isZero();assertThat(result.unassignedJobIds()).isEmpty();assertThat(result.diagnostics()).isEmpty();
  assertThat(PlanValidator.validate(f.carrier,result.plan(),f.request,f.config).valid()).isTrue();
 }}
 @Test void explicitInitialAlgorithmKeepsZeroButOfflineAndReplanningRejectIt() {try(var f=new OptimizationFixture()) {
  f.service("job",1,0,500);
  var problem=MatsimJspritFactory.createRoutingProblemBuilder(f.carrier,f.costs.getNetwork()).setRoutingCost(f.costs).build();
  var algorithm=HAGRIDRouterUtils.configureAlgorithm(problem,f.config,1337,0,HAGRIDRouterUtils.AlgorithmPhase.INITIAL);
  assertThat(algorithm.getMaxIterations()).isZero();
  assertThatThrownBy(()->HAGRIDRouterUtils.configureAlgorithm(problem,f.config,1337,0,HAGRIDRouterUtils.AlgorithmPhase.OFFLINE)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,1337,0)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new CarrierOptimizationService().optimizeInitial(f.carrier,f.costs,-1,25200,0)).isInstanceOf(IllegalArgumentException.class);
 }}
 @Test void zeroStillRejectsUnassignedJobBeforeStandaloneTaskPublication() {try(var f=new OptimizationFixture()) {
  f.service("oversized",11,0,500);
  var task=new JspritCarrierTask(f.carrier,f.costs,new AtomicInteger(),1,f.costs.getNetwork(),0,0,null);
  assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid initial result");
  assertThat(f.carrier.getSelectedPlan()).isNull();assertThat(f.carrier.getPlans()).isEmpty();
 }}
 @Test void zeroDoesNotBecomeAnAllowedReplanningOrOfflineConfiguration() {
  for(String key:new String[]{"optimization.replanningIterations","optimization.offlineIterations"})
   assertThatThrownBy(()->HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of(key,"0")),Map.of())).isInstanceOf(IllegalArgumentException.class);
 }
}