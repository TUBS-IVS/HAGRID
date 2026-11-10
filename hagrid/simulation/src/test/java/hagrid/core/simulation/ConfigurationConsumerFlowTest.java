package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.ConfigUtils;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.api.core.v01.Id;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.FreightActivity;
import static org.assertj.core.api.Assertions.*;

class ConfigurationConsumerFlowTest {
    @Test void configuredDrtNumbersReachNativeMatsimConfig() {
        var launch=SimulationRunnerUtils.parseScenario("concept=drt_baseline,date=2025-05-13,"
                +"drt.stopDurationSeconds=90,drt.maxWaitTimeSeconds=700,drt.zoneCellSizeMeters=1500,drt.rebalancingIntervalSeconds=900");
        var nativeConfig=ConfigUtils.createConfig();
        FreightScenarioConfiguration.apply(nativeConfig,launch);
        var drt=MultiModeDrtConfigGroup.get(nativeConfig).getModalElements().iterator().next();
        assertThat(drt.getStopDuration()).isEqualTo(90);
        assertThat(drt.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet().maxWaitTime).isEqualTo(700);
        assertThat(drt.getRebalancingParams().orElseThrow().getInterval()).isEqualTo(900);
        assertThat(((org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams)
                drt.getRebalancingParams().orElseThrow().getZoneSystemParams()).getCellSize()).isEqualTo(1500);
    }
    @Test void configuredTourDurationAndPenaltyReachOvertimeScoring() {
        var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,solverMaxRouteDuration=60,scoring.overtimePenalty=9");
        var costs=new ScoringFunctions.CarrierCostTracker();
        var scoring=new ScoringFunctions.DriversActivityScoring(costs,launch.getResolvedRoutingConfig().scoring());
        scoring.handleFirstActivity(activity(0));
        scoring.handleActivity(activity(60));
        assertThat(costs.overtimeCosts).isZero();
        scoring.handleActivity(activity(61));
        assertThat(costs.overtimeCosts).isEqualTo(9);
        scoring.handleActivity(activity(90));
        assertThat(costs.overtimeCosts).isEqualTo(9);
    }
    @Test void invalidDrtNumbersAreRejectedBeforeComposition() {
        for(String setting:new String[]{"drt.stopDurationSeconds=-1","drt.zoneCellSizeMeters=NaN", "drt.rebalancingIntervalSeconds=1.5",
                "drt.serviceBeginSeconds=90000", "drt.returnWindowSeconds=90000", "drt.maxTravelTimeAlpha=0.5"})
            assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept=drt_baseline,date=2025-05-13,"+setting))
                    .isInstanceOf(IllegalArgumentException.class);
    }
    private static FreightActivity activity(double start){
        var act=PopulationUtils.createActivityFromLinkId("delivery",Id.createLinkId("stop"));
        act.setStartTime(start);act.setEndTime(start);
        return new FreightActivity(act,TimeWindow.newInstance(0,100000));
    }
}
