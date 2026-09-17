package hagrid.analysis;

import hagrid.analysis.CarrierXmlParser.ParsedCarrier;
import hagrid.analysis.CarrierXmlParser.ParsedService;
import hagrid.analysis.CarrierXmlParser.ParsedTour;
import hagrid.analysis.CarrierXmlParser.TourAct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("DashboardGenerator — authoritative routed-parcel accounting")
class DashboardParcelStatsTest {

    private static ParsedService svc(String id, int cap) {
        return new ParsedService(id, "dhl", "link", cap, 120, 72000, Map.of());
    }

    private static TourAct serviceAct(String serviceId) {
        return new TourAct("service", serviceId, "link", 0, 0, 0);
    }

    private static ParsedCarrier deliveryCarrier(List<ParsedService> services, List<ParsedTour> tours) {
        return new ParsedCarrier("dhl", "delivery", "dhl", services.size(), 0, 0, 0,
                List.of(), services, tours, 0, Map.of(), Map.of(), Map.of());
    }

    @Test
    @DisplayName("sums capacityDemand over ALL routed service-acts (exclusion-independent)")
    void sumsAllRoutedParcels() {
        var services = List.of(svc("s1", 2), svc("s2", 3), svc("s3", 1));
        var tour1 = new ParsedTour("v0", "dhl", "t0", List.of(),
                List.of(serviceAct("s1"), serviceAct("s2")));
        var tour2 = new ParsedTour("v1", "dhl", "t1", List.of(),
                List.of(serviceAct("s3")));
        var carrier = deliveryCarrier(services, List.of(tour1, tour2));

        var stats = DashboardGenerator.computeRoutedParcelStats(List.of(carrier), (c, vid) -> 30);

        assertThat(stats.totalServices()).isEqualTo(3);
        assertThat(stats.totalParcels()).isEqualTo(6); // 2+3+1
        assertThat(stats.totalDemand()).isEqualTo(6);
        assertThat(stats.unresolvedServiceRefs()).isZero();
        // mean per-tour load: tour1 = 5/30, tour2 = 1/30
        assertThat(stats.avgLoadFactor()).isCloseTo((5.0 / 30 + 1.0 / 30) / 2, within(1e-9));
    }

    @Test
    @DisplayName("unresolved serviceId is tallied, NOT silently counted as 1 parcel")
    void unresolvedServiceIsNotCountedAsOne() {
        var services = List.of(svc("s1", 2));
        var tour = new ParsedTour("v0", "dhl", "t0", List.of(),
                List.of(serviceAct("s1"), serviceAct("ghost")));
        var carrier = deliveryCarrier(services, List.of(tour));

        var stats = DashboardGenerator.computeRoutedParcelStats(List.of(carrier), (c, vid) -> 30);

        assertThat(stats.totalServices()).isEqualTo(2);      // both acts observed
        assertThat(stats.totalParcels()).isEqualTo(2);       // only s1 counted, ghost NOT +1
        assertThat(stats.unresolvedServiceRefs()).isEqualTo(1);
    }
}
