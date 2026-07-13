package hagrid.freight;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

/**
 * Pins the HAGRID-local patch in NetworkBasedTransportCosts (null-path guard for
 * disconnected networks): unroutable relations must yield Double.MAX_VALUE instead
 * of throwing an NPE. Guards the patch across freight-source resyncs.
 */
public class NetworkBasedTransportCostsGuardTest {

	@Test
	void disconnectedNetwork_returnsMaxValue_insteadOfNpe() {
		// two-component network: n1->n2 (link a), n3->n4 (link b), no connection between them
		Network network = NetworkUtils.createNetwork();
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(0, 1000));
		Node n4 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n4"), new Coord(100, 1000));
		Link a = NetworkUtils.createAndAddLink(network, Id.createLinkId("a"), n1, n2, 100, 10, 500, 1);
		Link b = NetworkUtils.createAndAddLink(network, Id.createLinkId("b"), n3, n4, 100, 10, 500, 1);

		NetworkBasedTransportCosts.Builder builder = NetworkBasedTransportCosts.Builder.newInstance(network);
		builder.addVehicleTypeSpecificCosts("guardType", 1.0, 1.0, 1.0);
		NetworkBasedTransportCosts costs = builder.build();

		VehicleTypeImpl jspritType = VehicleTypeImpl.Builder.newInstance("guardType")
				.setMaxVelocity(10.0).build();
		Vehicle vehicle = VehicleImpl.Builder.newInstance("guardVehicle")
				.setStartLocation(Location.newInstance(a.getId().toString()))
				.setType(jspritType).build();

		double distance = costs.getDistance(
				Location.newInstance(a.getId().toString()),
				Location.newInstance(b.getId().toString()),
				0.0, vehicle);
		Assertions.assertEquals(Double.MAX_VALUE, distance,
				"unroutable relation must hit the null-path guard, not NPE");

		double time = costs.getTransportTime(
				Location.newInstance(a.getId().toString()),
				Location.newInstance(b.getId().toString()),
				0.0, null, vehicle);
		Assertions.assertEquals(Double.MAX_VALUE, time);
	}
}
