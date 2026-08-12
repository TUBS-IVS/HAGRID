package hagrid.analysis;

import hagrid.analysis.CarrierXmlParser.ParsedCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DashboardGenerator")
class DashboardGeneratorTest {

    private static ParsedCarrier carrier(String id, Map<String, String> attrs) {
        return new ParsedCarrier(id, "delivery", id, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), 0, attrs, Map.of(), Map.of());
    }

    @Test
    @DisplayName("sumIntAttr sums an int carrier attribute, treating missing/garbage as 0")
    void sumsIntAttributeAcrossCarriers() {
        ParsedCarrier withUnassigned = carrier("dhl",
                Map.of("unassignedParcels", "7", "unassignedJobs", "2"));
        ParsedCarrier withoutAttr = carrier("hermes", Map.of());
        ParsedCarrier garbageAttr = carrier("dpd", Map.of("unassignedParcels", "n/a"));

        List<ParsedCarrier> carriers = List.of(withUnassigned, withoutAttr, garbageAttr);

        assertThat(DashboardGenerator.sumIntAttr(carriers, "unassignedParcels")).isEqualTo(7);
        assertThat(DashboardGenerator.sumIntAttr(carriers, "unassignedJobs")).isEqualTo(2);
    }
}
