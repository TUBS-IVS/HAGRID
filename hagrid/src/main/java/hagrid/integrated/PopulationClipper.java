package hagrid.integrated;

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

    private PopulationClipper() {}

    public static Population clip(Population full, Geometry serviceArea) {
        PreparedGeometry prepared = new PreparedGeometryFactory().create(serviceArea);
        Population out = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        for (Person person : full.getPersons().values()) {
            Coord anchor = firstActivityCoord(person);
            if (anchor != null && prepared.contains(
                    GF.createPoint(new Coordinate(anchor.getX(), anchor.getY())))) {
                out.addPerson(person);
            }
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
