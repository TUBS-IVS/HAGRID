package hagrid.integrated.drt;

import hagrid.HagridPaths;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HagridPaths rail getters")
class RailPathsTest {

    @Test
    @DisplayName("rail staged-input + run-scoped output getters resolve under the Lausitz input/run roots")
    void railGettersResolve() {
        HagridPaths paths = new HagridPaths(StudyArea.LAUSITZ_HOYERSWERDA);
        paths.initializeRun("RAILTEST");

        assertThat(paths.lausitzTransitScheduleRaw())
                .endsWith(Path.of("transit", "lausitz-transitSchedule.xml.gz").toString());
        assertThat(paths.lausitzTransitVehiclesRaw())
                .endsWith(Path.of("transit", "lausitz-transitVehicles.xml.gz").toString());
        assertThat(paths.lausitzVehicleTypes())
                .endsWith(Path.of("vehicles", "lausitz-vehicle-types.xml").toString());
        assertThat(paths.railScheduleFiltered()).contains("RAILTEST").endsWith("rail-transitSchedule.xml.gz");
        assertThat(paths.railTransitVehiclesFiltered()).contains("RAILTEST").endsWith("rail-transitVehicles.xml.gz");
    }
}
