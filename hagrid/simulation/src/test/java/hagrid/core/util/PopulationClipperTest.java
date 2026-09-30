package hagrid.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PopulationClipper")
class PopulationClipperTest {

    private Geometry square() {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(1000, 0),
                new Coordinate(1000, 1000), new Coordinate(0, 1000), new Coordinate(0, 0)
        });
    }

    private Person personWithHome(String id, double x, double y) {
        Population pop = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        Person p = pf.createPerson(Id.createPersonId(id));
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(x, y)));
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        return p;
    }

    @Test
    @DisplayName("keeps persons whose home activity is inside the area")
    void keepsInside() {
        Population full = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        full.addPerson(personWithHome("inside", 500, 500));
        full.addPerson(personWithHome("outside", 9000, 9000));

        Population clipped = PopulationClipper.clip(full, square());

        assertThat(clipped.getPersons()).containsKey(Id.createPersonId("inside"));
        assertThat(clipped.getPersons()).doesNotContainKey(Id.createPersonId("outside"));
    }

    private Person personWithoutAnyCoord(String id) {
        Population pop = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        PopulationFactory pf = pop.getFactory();
        Person p = pf.createPerson(Id.createPersonId(id));
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromLinkId("home", Id.createLinkId("l1")));
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        return p;
    }

    @Test
    @DisplayName("persons without any activity coordinate are still dropped, but counted in a warning")
    void coordlessPersonsAreCountedNotSilentlyLost() {
        Population full = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        full.addPerson(personWithHome("inside", 500, 500));
        full.addPerson(personWithHome("outside", 9000, 9000));
        full.addPerson(personWithoutAnyCoord("nocoord"));

        try (LogCapture log = LogCapture.of(PopulationClipper.class)) {
            Population clipped = PopulationClipper.clip(full, square());
            // behaviour unchanged: only the inside person survives
            assertThat(clipped.getPersons()).containsOnlyKeys(Id.createPersonId("inside"));
            // 'outside' is a normal clip, 'nocoord' is a data defect -- only the latter warns
            assertThat(log.warnings()).singleElement().satisfies(m ->
                    assertThat(m).contains("1 of 3").containsIgnoringCase("no coordinate"));
        }
    }

    @Test
    @DisplayName("a population where every person has a coordinate clips without a warning")
    void completePopulationIsQuiet() {
        Population full = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());
        full.addPerson(personWithHome("inside", 500, 500));
        full.addPerson(personWithHome("outside", 9000, 9000));
        try (LogCapture log = LogCapture.of(PopulationClipper.class)) {
            PopulationClipper.clip(full, square());
            assertThat(log.warnings()).isEmpty();
        }
    }
}
