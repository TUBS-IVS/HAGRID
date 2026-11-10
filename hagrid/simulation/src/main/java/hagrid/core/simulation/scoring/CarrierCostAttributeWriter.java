package hagrid.core.simulation.scoring;

import org.matsim.freight.carriers.Carrier;

/** Stable attributes for existing analysis consumers, written from one finished snapshot. */
public final class CarrierCostAttributeWriter {
    private CarrierCostAttributeWriter() {}
    public static void write(Carrier carrier, CarrierCostBreakdown costs) {
        carrier.getAttributes().putAttribute("costDefinitionVersion", "hagrid-carrier-costs-v1");
        carrier.getAttributes().putAttribute("costTotal", costs.totalCosts());
        carrier.getAttributes().putAttribute("costFix", costs.fixed());
        carrier.getAttributes().putAttribute("costDistance", costs.distance());
        carrier.getAttributes().putAttribute("costTime", costs.travelTime());
        carrier.getAttributes().putAttribute("costActivity", costs.activity());
        carrier.getAttributes().putAttribute("costOvertime", costs.overtime());
        carrier.getAttributes().putAttribute("costTimeWindowPenalty", costs.timeWindowPenalty());
        carrier.getAttributes().putAttribute("totalDistanceMeters", costs.totalDistanceMeters());
        carrier.getAttributes().putAttribute("totalTravelTimeSeconds", costs.totalTravelTimeSeconds());
        carrier.getAttributes().putAttribute("uTurnCount", costs.uTurnCount());
        carrier.getAttributes().putAttribute("uncertainReversalCount", costs.uncertainReversalCount());
        carrier.getAttributes().putAttribute("chargedReversalCount", costs.chargedReversalCount());
    }
}
