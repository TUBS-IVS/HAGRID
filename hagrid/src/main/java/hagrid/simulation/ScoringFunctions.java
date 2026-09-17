package hagrid.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.FreightActivity;
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.vehicles.Vehicle;


import java.util.HashSet;
import java.util.Set;

/**
 * Defines carrier scoring function (factory).
 * <p>
 * The score is a MATSim utility value used for replanning (including soft penalties
 * like U-turn costs). The <b>actual monetary costs</b> (fix, distance, time, overtime,
 * time-window penalties) are tracked separately via {@link CarrierCostTracker} and
 * written to the carrier's attributes at the end of each iteration for analysis.
 *
 * @author stefan / HAGRID Team
 */
public class ScoringFunctions implements CarrierScoringFunctionFactory{

    private static final Logger LOGGER = LogManager.getLogger(ScoringFunctions.class);

    // =========================================================================
    //  CarrierCostTracker — tracks real monetary costs separately from score
    // =========================================================================

    /**
     * Accumulates the actual monetary cost components of a carrier's plan.
     * <p>
     * All scoring components write their cost contributions here. At the end of
     * scoring, {@link CostAttributeWriter} persists these values as carrier
     * attributes so they can be read for analysis without confusing them with
     * the MATSim utility score (which also contains soft penalties).
     * <p>
     * All values are stored as <b>positive</b> numbers (€ or cost units).
     */
    public static class CarrierCostTracker {
        /** Vehicle fixed costs (per vehicle employed). */
        double fixCosts = 0.0;
        /** Distance-dependent costs (€/m × distance). */
        double distanceCosts = 0.0;
        /** Time-dependent costs (€/s × travel time). */
        double timeCosts = 0.0;
        /** Activity/service handling time costs. */
        double activityCosts = 0.0;
        /** Penalty for exceeding maximum work duration. */
        double overtimeCosts = 0.0;
        /** Penalty for missed time windows. */
        double timeWindowPenaltyCosts = 0.0;
        /** Total distance driven (meters). */
        double totalDistanceMeters = 0.0;
        /** Total travel time (seconds). */
        double totalTravelTimeSeconds = 0.0;
        /** Number of U-turns detected on routes. */
        int uTurnCount = 0;

        /** Sum of all real monetary cost components. */
        public double getTotalCosts() {
            return fixCosts + distanceCosts + timeCosts + activityCosts
                    + overtimeCosts + timeWindowPenaltyCosts;
        }
    }

    /**
     * Scoring component that writes the tracked cost breakdown to carrier attributes.
     * Does not contribute to the MATSim score itself (returns 0).
     */
    static class CostAttributeWriter implements SumScoringFunction.BasicScoring {
        private final Carrier carrier;
        private final CarrierCostTracker tracker;

        CostAttributeWriter(Carrier carrier, CarrierCostTracker tracker) {
            this.carrier = carrier;
            this.tracker = tracker;
        }

        @Override
        public void finish() {
            carrier.getAttributes().putAttribute("costTotal", tracker.getTotalCosts());
            carrier.getAttributes().putAttribute("costFix", tracker.fixCosts);
            carrier.getAttributes().putAttribute("costDistance", tracker.distanceCosts);
            carrier.getAttributes().putAttribute("costTime", tracker.timeCosts);
            carrier.getAttributes().putAttribute("costActivity", tracker.activityCosts);
            carrier.getAttributes().putAttribute("costOvertime", tracker.overtimeCosts);
            carrier.getAttributes().putAttribute("costTimeWindowPenalty", tracker.timeWindowPenaltyCosts);
            carrier.getAttributes().putAttribute("totalDistanceMeters", tracker.totalDistanceMeters);
            carrier.getAttributes().putAttribute("totalTravelTimeSeconds", tracker.totalTravelTimeSeconds);
            carrier.getAttributes().putAttribute("uTurnCount", tracker.uTurnCount);
            LOGGER.debug("Carrier {} costs: total={} (fix={} dist={} time={} act={} ot={} tw={})",
                    carrier.getId(), String.format("%.2f", tracker.getTotalCosts()),
                    String.format("%.2f", tracker.fixCosts),
                    String.format("%.2f", tracker.distanceCosts),
                    String.format("%.2f", tracker.timeCosts),
                    String.format("%.2f", tracker.activityCosts),
                    String.format("%.2f", tracker.overtimeCosts),
                    String.format("%.2f", tracker.timeWindowPenaltyCosts));
        }

        @Override
        public double getScore() {
            return 0.0; // does not contribute to the MATSim utility score
        }
    }

    /**
     *
     * Example activity scoring that penalizes missed time-windows with 1.0 per second.
     *
     * @author stefan
     *
     */
    static class DriversActivityScoring implements SumScoringFunction.BasicScoring, SumScoringFunction.ActivityScoring {

        private double score;
        private final CarrierCostTracker costTracker;

        private final double timeParameter = 0.008;

        private final double missedTimeWindowPenalty = 5.0;
        private final double maxWorkDuration = 7.5 * 3600;

        private double startTime = -1;
        
        private boolean isExceedingWorkTime = false;

        public DriversActivityScoring(CarrierCostTracker costTracker) {
            super();
            this.costTracker = costTracker;
        }

        @Override
        public void finish() {
//			try {
//				fileWriter.close();
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
        }

        @Override
        public double getScore() {
            return score;
        }

        @Override
        public void handleFirstActivity(Activity act) {
            handleActivity(act);
        }

        @Override
        public void handleActivity(Activity act) {
            if(act instanceof FreightActivity) {
            	double actStartTime = act.getStartTime().seconds();

            	if(startTime < 0) {
            		startTime = actStartTime;
            	}
//            	
            	if((actStartTime - startTime) > maxWorkDuration) {

            		if(!isExceedingWorkTime) {
            			score += (missedTimeWindowPenalty * -1);
            			costTracker.overtimeCosts += missedTimeWindowPenalty;
            		}
            	}

//                log.info(act + " start: " + Time.writeTime(actStartTime));
                TimeWindow tw = ((FreightActivity) act).getTimeWindow();
                if(actStartTime > tw.getEnd()){
                    double penalty_score = (-1)*(actStartTime - tw.getEnd())*missedTimeWindowPenalty;
                    if (penalty_score > 0.0) {
                        throw new IllegalStateException("penalty score must be negative but was " + penalty_score);
                    }
//                    log.info("penalty " + penalty_score);
                    score += penalty_score;
                    costTracker.timeWindowPenaltyCosts += Math.abs(penalty_score);

                }
                double actTimeCosts = (act.getEndTime().seconds()-actStartTime)*timeParameter;
//                log.info("actCosts " + actTimeCosts);
                if (actTimeCosts < 0.0) {
                    throw new IllegalStateException("actTimeCosts must be non-negative but was " + actTimeCosts);
                }
                score += actTimeCosts * (-1);
                costTracker.activityCosts += actTimeCosts;
//                try {
//					fileWriter.write("actLinkId="+ act.getLinkId() + "; actArrTime=" + Time.writeTime(actStartTime) +
//							"; twEnd=" + tw.getEnd() + "; minTooLate=" + Time.writeTime(Math.max(0, actStartTime-tw.getEnd()))
//							+ "; penaltyMissedTW=" + (Math.max(0, actStartTime-tw.getEnd())*missedTimeWindowPenalty) +
//							"; actCosts=" +actTimeCosts + "\n");
//				} catch (IOException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
            }
        }

        @Override
        public void handleLastActivity(Activity act) {
            handleActivity(act);
        }

    }

    static class VehicleEmploymentScoring implements SumScoringFunction.BasicScoring {

        private final Carrier carrier;
        private final CarrierCostTracker costTracker;

        public VehicleEmploymentScoring(Carrier carrier, CarrierCostTracker costTracker) {
            super();
            this.carrier = carrier;
            this.costTracker = costTracker;
        }

        @Override
        public void finish() {

        }

        @Override
        public double getScore() {
            double score = 0.;
            CarrierPlan selectedPlan = carrier.getSelectedPlan();
            if(selectedPlan == null) return 0.;            
            for(ScheduledTour tour : selectedPlan.getScheduledTours()){    
            	
            	double tourStartTime = tour.getDeparture();
            	
                if(!tour.getTour().getTourElements().isEmpty()){
                    double fixCost = tour.getVehicle().getType().getVehicleCostInformation().getFix();
                    score += (-1) * fixCost;
                    costTracker.fixCosts += fixCost;
                }
            }

            return score;
        }

    }

	/**
	 * Example leg scoring.
	 *
	 * @author stefan
	 *
	 */
	public static class SimpleDriversLegScoring implements SumScoringFunction.BasicScoring, SumScoringFunction.LegScoring {

		private double score = 0.0;
		private final Network network;
		private final Carrier carrier;
		private final Set<CarrierVehicle> employedVehicles;
		private final double uTurnPenaltyCost;
		private final CarrierCostTracker costTracker;

		public SimpleDriversLegScoring( Carrier carrier, Network network, double uTurnPenaltyCost,
				CarrierCostTracker costTracker ) {
			super();
			this.network = network;
			this.carrier = carrier;
			this.uTurnPenaltyCost = uTurnPenaltyCost;
			this.costTracker = costTracker;
			employedVehicles = new HashSet<>();
		}

		@Override
		public void finish() { }

		@Override
		public double getScore() {
			return score;
		}

		private double getTimeParameter(CarrierVehicle vehicle) {
			return vehicle.getType().getCostInformation().getCostsPerSecond();
		}

		private double getDistanceParameter(CarrierVehicle vehicle) {
			return vehicle.getType().getCostInformation().getCostsPerMeter();
		}

		@Override
		public void handleLeg(Leg leg) {
			if(leg.getRoute() instanceof NetworkRoute nRoute){
				Id<Vehicle> vehicleId = nRoute.getVehicleId();
				CarrierVehicle vehicle = CarriersUtils.getCarrierVehicle(carrier, vehicleId);
				Gbl.assertNotNull(vehicle);
				if(!employedVehicles.contains(vehicle)){
					employedVehicles.add(vehicle);
				}
				double distance = 0.0;
				if(leg.getRoute() instanceof NetworkRoute){
					Link startLink = network.getLinks().get(leg.getRoute().getStartLinkId());
					distance += startLink.getLength();
					for(Id<Link> linkId : ((NetworkRoute) leg.getRoute()).getLinkIds()){
						distance += network.getLinks().get(linkId).getLength();

					}
					distance += network.getLinks().get(leg.getRoute().getEndLinkId()).getLength();

				}

				double distanceCosts = distance*getDistanceParameter(vehicle);
				if (!(distanceCosts >= 0.0)) throw new AssertionError("distanceCosts must be positive");
				score += (-1) * distanceCosts;
				costTracker.distanceCosts += distanceCosts;
				costTracker.totalDistanceMeters += distance;

				double timeCosts = leg.getTravelTime().seconds() *getTimeParameter(vehicle);
				if (!(timeCosts >= 0.0)) throw new AssertionError("timeCosts must be positive");
				score += (-1) * timeCosts;
				costTracker.timeCosts += timeCosts;
				costTracker.totalTravelTimeSeconds += leg.getTravelTime().seconds();

				// U-turn penalty: check consecutive links for reverse-link pairs
				if (uTurnPenaltyCost > 0.0 && leg.getRoute() instanceof NetworkRoute) {
					int uTurnCount = countUTurns(nRoute);
					if (uTurnCount > 0) {
						score += (-1) * uTurnCount * uTurnPenaltyCost;
						costTracker.uTurnCount += uTurnCount;
					}
				}

			}
		}

		/**
		 * Counts reverse-link U-turns along a network route.
		 * A U-turn is detected when consecutive links are reverse pairs
		 * (link A→B followed by link B→A).
		 */
		private int countUTurns(NetworkRoute route) {
			java.util.List<Id<Link>> linkIds = route.getLinkIds();
			// Build the full link sequence: start → intermediate → end
			java.util.List<Id<Link>> allLinks = new java.util.ArrayList<>(linkIds.size() + 2);
			allLinks.add(route.getStartLinkId());
			allLinks.addAll(linkIds);
			allLinks.add(route.getEndLinkId());

			int count = 0;
			for (int i = 0; i < allLinks.size() - 1; i++) {
				Link current = network.getLinks().get(allLinks.get(i));
				Link next = network.getLinks().get(allLinks.get(i + 1));
				if (current == null || next == null) continue;
				// Same link can appear consecutively (loop) — not a U-turn
				if (current.getId().equals(next.getId())) continue;
				// Reverse-link check: current goes A→B, next goes B→A
				if (current.getToNode().getId().equals(next.getFromNode().getId())
						&& current.getFromNode().getId().equals(next.getToNode().getId())) {
					count++;
				}
			}
			return count;
		}

	}


    static class TollScoring implements SumScoringFunction.BasicScoring, SumScoringFunction.ArbitraryEventScoring {

        private double score = 0.;

        private final Carrier carrier;

        private final Network network;

        private final VehicleTypeDependentRoadPricingCalculator roadPricing;

        public TollScoring(Carrier carrier, Network network, VehicleTypeDependentRoadPricingCalculator roadPricing) {
            this.carrier = carrier;
            this.roadPricing = roadPricing;
            this.network = network;
        }

        @Override
        public void handleEvent(Event event) {
            if(event instanceof LinkEnterEvent){
                CarrierVehicle carrierVehicle = getVehicle(((LinkEnterEvent) event).getVehicleId());
                if(carrierVehicle == null) throw new IllegalStateException("carrier vehicle missing");
                double toll = roadPricing.getTollAmount(carrierVehicle.getType().getId(),network.getLinks().get(((LinkEnterEvent) event).getLinkId()),event.getTime());
                if(toll > 0.) System.out.println("bing: vehicle " + carrierVehicle.getId() + " paid toll " + toll);
                score += (-1) * toll;
            }
        }

        private CarrierVehicle getVehicle(Id<Vehicle> vehicleId) {
            for(CarrierVehicle v : carrier.getCarrierCapabilities().getCarrierVehicles().values()){
                if(v.getId().equals(vehicleId)){
                    return v;
                }
            }
            return null;
        }

        @Override
        public void finish() {

        }

        @Override
        public double getScore() {
            return score;
        }
    }

    private final Network network;
    private final double uTurnPenaltyCost;

    public ScoringFunctions(Network network, double uTurnPenaltyCost) {
        super();
        this.network = network;
        this.uTurnPenaltyCost = uTurnPenaltyCost;
    }


	@Override
	public ScoringFunction createScoringFunction(Carrier carrier) {
		CarrierCostTracker costTracker = new CarrierCostTracker();

		SumScoringFunction sf = new SumScoringFunction();
		SimpleDriversLegScoring driverLegScoring = new SimpleDriversLegScoring(carrier, network, uTurnPenaltyCost, costTracker);
		VehicleEmploymentScoring vehicleEmployment = new VehicleEmploymentScoring(carrier, costTracker);
		DriversActivityScoring actScoring = new DriversActivityScoring(costTracker);
		CostAttributeWriter costWriter = new CostAttributeWriter(carrier, costTracker);
		sf.addScoringFunction(driverLegScoring);
		sf.addScoringFunction(vehicleEmployment);
		sf.addScoringFunction(actScoring);
		sf.addScoringFunction(costWriter);
		return sf;
	}



}
