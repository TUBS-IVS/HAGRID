package hagrid.core.routing;

import hagrid.core.simulation.CarrierInputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarrierVehicleTypes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ModalNetworkInputValidationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"length,NaN", "length,-1", "freespeed,NaN", "freespeed,0",
            "capacity,NaN", "capacity,-1", "lanes,NaN", "lanes,-1"})
    void firstAndOnlyInputMustHaveValidRoutingScalars(String field, double value) {
        var input = network();
        mutate(input, field, value);
        assertThatIllegalArgumentException().isThrownBy(() -> ModalNetworkImporter.merge(List.of(input), Map.of()))
                .withMessageContaining("road").withMessageContaining(field);
        assertThat(input.getLinks().get(Id.createLinkId("road"))).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"NaN,0,0", "0,Infinity,0", "0,0,Infinity"})
    void firstInputMustHaveFiniteCoordinates(double x, double y, double z) {
        var input = network();
        input.getNodes().get(Id.createNodeId("a")).setCoord(new Coord(x, y, z));
        assertThatIllegalArgumentException().isThrownBy(() -> ModalNetworkImporter.merge(List.of(input), Map.of()))
                .withMessageContaining("a").withMessageContaining("coordinates");
    }

    @Test
    void commonFileFastPathMustNotSkipValidation() {
        var input = network();
        mutate(input, "length", Double.NaN);
        var file = directory.resolve("network.xml");
        new NetworkWriter(input).write(file.toString());
        assertThatIllegalArgumentException().isThrownBy(() -> ModalNetworkImporter.load(file, Optional.empty(), Map.of()))
                .withMessageContaining("road").withMessageContaining("length");
    }

    @Test
    void carrierBoundaryMustAlsoValidateUnmergedScenarioGraph() {
        var input = network();
        mutate(input, "length", Double.NaN);
        assertThatIllegalArgumentException().isThrownBy(() ->
                CarrierInputValidator.validate(new Carriers(), new CarrierVehicleTypes(), input))
                .withMessageContaining("road").withMessageContaining("length");
    }

    @Test
    void staleAdjacencyCannotBeAcceptedAsTheAuthoritativeGraph() {
        var input = network();
        var link = input.getLinks().get(Id.createLinkId("road"));
        link.getFromNode().removeOutLink(link.getId());
        assertThatIllegalArgumentException().isThrownBy(() -> ModalNetworkImporter.merge(List.of(input), Map.of()))
                .withMessageContaining("road").withMessageContaining("adjacency");
    }

    @Test
    void finiteZeroLengthConnectorAndClosedCapacityRemainSupported() {
        var input = network();
        mutate(input, "length", 0);
        mutate(input, "capacity", 0);
        var output = ModalNetworkImporter.merge(List.of(input), Map.of());
        assertThat(output.getLinks().get(Id.createLinkId("road")).getLength()).isZero();
        assertThat(output.getLinks().get(Id.createLinkId("road")).getCapacity()).isZero();
    }

    private static void mutate(Network input, String field, double value) {
        var link = input.getLinks().get(Id.createLinkId("road"));
        switch (field) {
            case "length" -> link.setLength(value);
            case "freespeed" -> link.setFreespeed(value);
            case "capacity" -> link.setCapacity(value);
            case "lanes" -> link.setNumberOfLanes(value);
            default -> throw new IllegalArgumentException(field);
        }
    }

    private static Network network() {
        var network = NetworkUtils.createNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
        var link = NetworkUtils.createAndAddLink(network, Id.createLinkId("road"), a, b, 100, 10, 1000, 1);
        link.setAllowedModes(Set.of("car"));
        return network;
    }
}
