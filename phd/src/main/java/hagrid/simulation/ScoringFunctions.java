package hagrid.simulation;

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
import org.matsim.freight.carriers.controler.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controler.FreightActivity;
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.freight.carriers.usecases.chessboard.CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring;
import org.matsim.vehicles.Vehicle;


import java.util.HashSet;
import java.util.Set;

/**
 * Defines example carrier scoring function (factory).
 *
 * <p>Just saw that there are some Deprecations. Needs to be adapted.
 *
 * @author stefan
 *
 */
public class ScoringFunctions implements CarrierScoringFunctionFactory{

    /**
     *
     * Example activity scoring that penalizes missed time-windows with 1.0 per second.
     *
     * @author stefan
     *
     */
    static class DriversActivityScoring implements SumScoringFunction.BasicScoring, SumScoringFunction.ActivityScoring {

        private double score;

        private final double timeParameter = 0.008;

        private final double missedTimeWindowPenalty = 5.0;
        private final double maxWorkDuration = 7.5 * 3600;

        private double startTime = -1;
        
        private boolean isExceedingWorkTime = false;

        public DriversActivityScoring() {
            super();
//			try {
//				fileWriter = new FileWriter(new File("output/act_scoring_"+System.currentTimeMillis()+".txt"));
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
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
            		}
            	}

//                log.info(act + " start: " + Time.writeTime(actStartTime));
                TimeWindow tw = ((FreightActivity) act).getTimeWindow();
                if(actStartTime > tw.getEnd()){
                    double penalty_score = (-1)*(actStartTime - tw.getEnd())*missedTimeWindowPenalty;
                    assert penalty_score <= 0.0 : "penalty score must be negative";
//                    log.info("penalty " + penalty_score);
                    score += penalty_score;

                }
                double actTimeCosts = (act.getEndTime().seconds()-actStartTime)*timeParameter;
//                log.info("actCosts " + actTimeCosts);
                assert actTimeCosts >= 0.0 : "actTimeCosts must be positive";
                score += actTimeCosts*(-1);
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
        


        public VehicleEmploymentScoring(Carrier carrier) {
            super();
            this.carrier = carrier;
//			try {
//				fileWriter = new FileWriter(new File("output/veh_employment_"+System.currentTimeMillis()+".txt"));
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
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
                    score += (-1)*tour.getVehicle().getType().getVehicleCostInformation().getFix();                    
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

		public SimpleDriversLegScoring( Carrier carrier, Network network ) {
			super();
			this.network = network;
			this.carrier = carrier;
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
				double timeCosts = leg.getTravelTime().seconds() *getTimeParameter(vehicle);
				if (!(timeCosts >= 0.0)) throw new AssertionError("distanceCosts must be positive");
				score += (-1) * timeCosts;

			}
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

    public ScoringFunctions(Network network) {
        super();
        this.network = network;
    }


	@Override
	public ScoringFunction createScoringFunction(Carrier carrier) {
		SumScoringFunction sf = new SumScoringFunction();
		SimpleDriversLegScoring driverLegScoring = new SimpleDriversLegScoring(carrier, network);
		SimpleVehicleEmploymentScoring vehicleEmployment = new SimpleVehicleEmploymentScoring(carrier);
		DriversActivityScoring actScoring = new DriversActivityScoring();
		sf.addScoringFunction(driverLegScoring);
		sf.addScoringFunction(vehicleEmployment);
		sf.addScoringFunction(actScoring);
		return sf;
	}



}
