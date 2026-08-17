package hagrid.integrated;

import hagrid.utils.demand.Delivery;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryDistrictBuilderTest {

    private static Delivery delivery(double x, double y, String provider, int amount) {
        return Delivery.builder()
                .id(provider + "_" + x + "_" + y)
                .coordinate(new Coord(x, y))
                .provider(provider)
                .amount(amount)
                .parcelType(Delivery.ParcelType.B2C)
                .deliveryMode(Delivery.DeliveryMode.HOME)
                .build();
    }

    private static final DepotNetwork.Depot WEST = new DepotNetwork.Depot("west", new Coord(0, 0));
    private static final DepotNetwork.Depot EAST = new DepotNetwork.Depot("east", new Coord(1000, 0));

    @Test
    void poolsAllProvidersAtOneSegmentIntoOneStop() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 3),
                        delivery(10, 0, "hermes", 2),
                        delivery(10, 0, "gls", 1)),
                List.of(WEST, EAST), 300);

        assertEquals(1, ds.size());
        assertEquals(1, ds.get(0).stops().size(), "three providers at one segment = one stop");
        DeliveryDistrictBuilder.PooledStop stop = ds.get(0).stops().get(0);
        assertEquals(6, stop.totalParcels());
        assertEquals(3, stop.parts().size(), "original deliveries must survive for the overlay");
    }

    @Test
    void assignsEachStopToTheNearestOpenDepot() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 1), delivery(990, 0, "dhl", 1)),
                List.of(WEST, EAST), 300);

        assertEquals(2, ds.size());
        DeliveryDistrictBuilder.District west = ds.stream()
                .filter(d -> d.depot().id().equals("west")).findFirst().orElseThrow();
        DeliveryDistrictBuilder.District east = ds.stream()
                .filter(d -> d.depot().id().equals("east")).findFirst().orElseThrow();
        assertEquals(10.0, west.stops().get(0).coord().getX(), 1e-9);
        assertEquals(990.0, east.stops().get(0).coord().getX(), 1e-9);
    }

    @Test
    void singleOpenDepotYieldsOneCatchment() {
        List<DeliveryDistrictBuilder.District> ds = DeliveryDistrictBuilder.build(
                List.of(delivery(10, 0, "dhl", 1), delivery(990, 0, "gls", 1)),
                List.of(WEST), 300);

        assertEquals(1, ds.size());
        assertEquals(2, ds.get(0).stops().size());
        assertEquals("west", ds.get(0).depot().id());
    }

    @Test
    void isDeterministicAcrossRepeatedBuilds() {
        List<Delivery> in = List.of(delivery(10, 0, "dhl", 1), delivery(20, 5, "gls", 2),
                delivery(990, 0, "ups", 1));
        String a = DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 300).toString();
        String b = DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 300).toString();
        assertEquals(a, b);
    }

    @Test
    void rejectsEmptyDepotList() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryDistrictBuilder.build(
                List.of(delivery(0, 0, "dhl", 1)), List.of(), 300));
    }

    @Test
    void selectOpenDepotsKeepsCsvOrderAndDefaultsToAll() {
        java.util.Map<String, Coord> csv = new java.util.LinkedHashMap<>();
        csv.put("wittichenau", new Coord(0, 0));
        csv.put("hoy_sued", new Coord(1000, 0));
        csv.put("spreetal", new Coord(2000, 0));

        assertEquals(List.of("wittichenau", "hoy_sued", "spreetal"),
                DeliveryDistrictBuilder.selectOpenDepots(csv, null).stream()
                        .map(DepotNetwork.Depot::id).toList());
        assertEquals(List.of("wittichenau", "spreetal"),
                DeliveryDistrictBuilder.selectOpenDepots(csv, List.of("spreetal", "WITTICHENAU"))
                        .stream().map(DepotNetwork.Depot::id).toList());
    }

    @Test
    void selectOpenDepotsRejectsAnUnknownName() {
        java.util.Map<String, Coord> csv = java.util.Map.of("wittichenau", new Coord(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryDistrictBuilder.selectOpenDepots(csv, List.of("hoy_sued")));
    }

    /**
     * The 1c/1d consistency guarantee (spec D8/8): 1d splits at the job ceiling, 1c does not, and
     * both must still pick the SAME depot for every segment. Level 2 partitions, it never reassigns.
     */
    @Test
    void theSegmentToDepotMappingIsIndependentOfTheJobCeiling() {
        List<Delivery> in = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            in.add(delivery(i * 100, 0, "dhl", 1));
        }
        java.util.Map<String, String> withSplit = depotBySegment(
                DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), 3));
        java.util.Map<String, String> withoutSplit = depotBySegment(
                DeliveryDistrictBuilder.build(in, List.of(WEST, EAST), Integer.MAX_VALUE));

        assertEquals(withoutSplit, withSplit);
    }

    private static java.util.Map<String, String> depotBySegment(
            List<DeliveryDistrictBuilder.District> districts) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (DeliveryDistrictBuilder.District d : districts) {
            for (DeliveryDistrictBuilder.PooledStop s : d.stops()) {
                out.put(s.coord().getX() + "|" + s.coord().getY(), d.depot().id());
            }
        }
        return out;
    }
}
