package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.FreightActivity;

import static org.assertj.core.api.Assertions.assertThat;

class DriversActivityScoringTest {
    @Test
    void sevenHourWorkTimeThresholdAndOneOvertimePenalty() {
        var costs = new ScoringFunctions.CarrierCostTracker();
        var scoring = new ScoringFunctions.DriversActivityScoring(costs);
        scoring.handleFirstActivity(activity(0));
        // The shared default is seven hours, including overtime scoring.
        scoring.handleActivity(activity(25200));
        assertThat(costs.overtimeCosts).isZero();
        scoring.handleActivity(activity(25201));
        assertThat(costs.overtimeCosts).isEqualTo(5);
        scoring.handleLastActivity(activity(28000));
        assertThat(costs.overtimeCosts).isEqualTo(5);
        assertThat(scoring.getScore()).isEqualTo(-5);
    }

    private static FreightActivity activity(double start) {
        var activity = PopulationUtils.createActivityFromLinkId("delivery", Id.createLinkId("stop"));
        activity.setStartTime(start);
        activity.setEndTime(start); // zero service duration isolates the overtime component.
        return new FreightActivity(activity, TimeWindow.newInstance(0, 100000));
    }
}
