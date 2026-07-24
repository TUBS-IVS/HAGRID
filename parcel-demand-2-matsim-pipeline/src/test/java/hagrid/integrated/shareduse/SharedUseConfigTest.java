package hagrid.integrated.shareduse;

import hagrid.integrated.drt.DrtConfigComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DrtConfigComposer.composeSharedUse")
class SharedUseConfigTest {

    /** Baseline composed exactly like {@code DrtConfigComposerTest} (fact F). */
    private static Config composedBaseline() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        return config;
    }

    @Test
    @DisplayName("adds 2D load, all-day retry window, parcel selector-only replanning, non-scoring parcel activities")
    void sharedUseConfigHasLoadRetryStrategyAndActivityParams() {
        Config config = composedBaseline();
        DrtConfigComposer.composeSharedUse(config);

        DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

        // 2D load: passengers (seats scalar) + parcels
        var load = drt.addOrGetLoadParams();
        assertEquals(List.of("passengers", "parcels"), load.getDimensions());
        assertEquals("passengers", load.getMapFleetCapacity());
        assertEquals("passengers", load.getDefaultRequestDimension());

        // retry params present with an all-day pending window
        assertTrue(drt.getParameterSets("dvrpRequestRetry").iterator().hasNext());

        // parcel subpopulation: selector-only strategy (no innovation)
        boolean parcelSelector = config.replanning().getStrategySettings().stream()
                .anyMatch(s -> "parcel".equals(s.getSubpopulation())
                        && "ChangeExpBeta".equals(s.getStrategyName()) && s.getWeight() == 1.0);
        assertTrue(parcelSelector);
        boolean parcelInnovation = config.replanning().getStrategySettings().stream()
                .anyMatch(s -> "parcel".equals(s.getSubpopulation())
                        && !"ChangeExpBeta".equals(s.getStrategyName()));
        assertFalse(parcelInnovation);

        // parcel activity types exist and are non-scoring
        assertFalse(config.scoring().getActivityParams(SharedUse.ACT_DEPOT).isScoringThisActivityAtAll());
        assertFalse(config.scoring().getActivityParams(SharedUse.ACT_DELIVERY).isScoringThisActivityAtAll());
    }
}
