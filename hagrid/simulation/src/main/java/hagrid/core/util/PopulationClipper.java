package hagrid.core.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

/**
 * Clips a passenger population to a DRT service area. A person is kept when the
 * first activity of its selected plan (home anchor) lies inside the area.
 */
public final class PopulationClipper {

    private static final GeometryFactory GF = new GeometryFactory();
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(PopulationClipper.class);

    private PopulationClipper() {}

    public static Population clip(Population full, Geometry serviceArea) {
        PreparedGeometry prepared = new PreparedGeometryFactory().create(serviceArea);
        Population out = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        int noCoord = 0;
        for (Person person : full.getPersons().values()) {
            Coord anchor = firstActivityCoord(person);
            if (anchor == null) {
                noCoord++;
            } else if (prepared.contains(GF.createPoint(new Coordinate(anchor.getX(), anchor.getY())))) {
                out.addPerson(person);
            }
        }
        // Behaviour kept: such persons are dropped. Unlike "home outside the area" this is a data
        // defect, and it used to vanish without a trace.
        if (noCoord > 0) {
            LOG.warn("{} of {} persons have no coordinate on any activity of their selected plan and"
                    + " were dropped from the clip", noCoord, full.getPersons().size());
        }
        return out;
    }

    private static Coord firstActivityCoord(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) {
            return null;
        }
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act && act.getCoord() != null) {
                return act.getCoord();
            }
        }
        return null;
    }
}
