package hagrid.integrated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DepotNetwork")
class DepotNetworkTest {

    private static final DepotNetwork.Depot A = new DepotNetwork.Depot("depot_A", new Coord(0.0, 0.0));
    private static final DepotNetwork.Depot B = new DepotNetwork.Depot("depot_B", new Coord(1000.0, 0.0));
    private static final DepotNetwork.Depot C = new DepotNetwork.Depot("depot_C", new Coord(0.0, 1000.0));

    @Test
    @DisplayName("nearestDepot returns the closest depot by Euclidean distance")
    void nearest() {
        DepotNetwork net = new DepotNetwork(List.of(A, B, C));
        assertThat(net.nearestDepot(new Coord(900.0, 50.0))).isEqualTo(B);
        assertThat(net.nearestDepot(new Coord(10.0, 10.0))).isEqualTo(A);
        assertThat(net.nearestDepot(new Coord(50.0, 900.0))).isEqualTo(C);
    }

    @Test
    @DisplayName("depots() is the immutable configured list")
    void depotsImmutable() {
        DepotNetwork net = new DepotNetwork(List.of(A, B));
        assertThat(net.depots()).containsExactly(A, B);
        assertThatThrownBy(() -> net.depots().add(C))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an empty depot list is rejected")
    void rejectsEmpty() {
        assertThatThrownBy(() -> new DepotNetwork(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one depot");
    }
}
