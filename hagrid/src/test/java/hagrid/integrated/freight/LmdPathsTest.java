package hagrid.integrated.freight;

import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HagridPaths LMD getters")
class LmdPathsTest {

    @Test
    @DisplayName("LMD input + run-scoped output getters resolve under the Lausitz roots")
    void lmdGettersResolve() {
        HagridPaths paths = new HagridPaths(StudyArea.LAUSITZ_HOYERSWERDA);
        paths.initializeRun("LMD_BASELINE_13052025");

        assertThat(paths.lmdDepotCsv()).contains("lausitz").endsWith(Path.of("hubs", "lmd-depots.csv").toString());
        assertThat(paths.lmdDemandShapefile()).contains("lausitz")
                .endsWith(Path.of("demand", "hagrid_parcel_demand_2025-05-13_(Tuesday).shp").toString());
        assertThat(paths.lmdVehicleTypes()).contains("lausitz").endsWith(Path.of("vehicles", "lmd-vehicle-types.xml").toString());
        assertThat(paths.lmdCarriersRouted())
                .endsWith(Path.of("LMD_BASELINE_13052025", "carriers",
                        "LMD_BASELINE_13052025_lmd_carriers_routed.xml").toString());
    }
}
