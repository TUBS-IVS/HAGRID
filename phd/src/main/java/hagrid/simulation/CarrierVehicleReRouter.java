package hagrid.simulation;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.inject.Inject;
import com.graphhopper.jsprit.analysis.toolbox.StopWatch;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.listener.VehicleRoutingAlgorithmListeners;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.algorithm.state.UpdateEndLocationIfRouteIsOpen;
import com.graphhopper.jsprit.core.algorithm.termination.IterationWithoutImprovementTermination;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.constraint.ServiceDeliveriesFirstConstraint;
import com.graphhopper.jsprit.core.problem.constraint.SwitchNotFeasible;
import com.graphhopper.jsprit.core.problem.constraint.VehicleDependentTimeWindowConstraints;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingActivityCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.io.algorithm.AlgorithmConfig;
import com.graphhopper.jsprit.io.algorithm.AlgorithmConfigXmlReader;
import com.graphhopper.jsprit.io.algorithm.VehicleRoutingAlgorithms;

import hagrid.utils.routing.DepartureTimeReScheduler;
import hagrid.utils.routing.UpdateDepartureTimeAndPracticalTimeWindows;

import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;

import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.freight.carriers.CarrierVehicleTypes;

import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.GenericPlanStrategyModule;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.router.util.TravelTime;

public final class CarrierVehicleReRouter {

	private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(CarrierVehicleReRouter.class);

	private final Network carNetwork;

	private final CarrierVehicleTypes vehicleTypes;

	private final TravelTime travelTimes;

	private String pathAlgo;

	private static final double MAXROUTEDURATIONHOUR = 7.5;
	private static final int MAXROUTEDURATION = (int) MAXROUTEDURATIONHOUR * 3600;

	private static final int STARTOPTIMIZATION = 0;
	private static final int STOPOPTIMIZATION = 100;

	private static final int MAXREPLANNINGSIZE = 32;

	private final double timeParameter = 0.008;

	private final VehicleRoutingActivityCosts activityCosts;

	private final Boolean isUsingZones;

	private final Map<String, VRPTransportCosts> byModeVRPTransportCosts;

	private final Network bikeNetwork;

	public CarrierVehicleReRouter(Network carNetwork, Network bikeNetwork, CarrierVehicleTypes vehicleTypes, TravelTime travelTimes,
			Boolean isUsingZones, Map<String, VRPTransportCosts> byModeVRPTransportCosts) {
		super();
		this.carNetwork = carNetwork;
		this.bikeNetwork = bikeNetwork;
		this.vehicleTypes = vehicleTypes;
		this.travelTimes = travelTimes;
		this.isUsingZones = isUsingZones;
		this.byModeVRPTransportCosts = byModeVRPTransportCosts;

		this.activityCosts = createVehicleRoutingActivityCosts();

	}

	private VehicleRoutingActivityCosts createVehicleRoutingActivityCosts() {
		// Activity costs
		VehicleRoutingActivityCosts activityCosts = new VehicleRoutingActivityCosts() {

			private final double penalty4missedTws = 5.0;

			@Override
			public double getActivityCost(TourActivity act, double arrivalTime, Driver arg2, Vehicle vehicle) {
				double tooLate = Math.max(0, arrivalTime - act.getTheoreticalLatestOperationStartTime());
				double waiting = Math.max(0, act.getTheoreticalEarliestOperationStartTime() - arrivalTime);
				double service = act.getOperationTime() * vehicle.getType().getVehicleCostParams().perWaitingTimeUnit;
				return penalty4missedTws * tooLate
						+ vehicle.getType().getVehicleCostParams().perWaitingTimeUnit * waiting + service;

			}

			@Override
			public double getActivityDuration(TourActivity act, double arrivalTime, Driver driver, Vehicle vehicle) {
				return Math.max(0, act.getEndTime() - act.getArrTime());
			}
		};
		return activityCosts;
	}

	public GenericPlanStrategy<CarrierPlan, Carrier> createStrategy() {

		GenericPlanStrategyImpl<CarrierPlan, Carrier> replanningStrat = new GenericPlanStrategyImpl<CarrierPlan, Carrier>(
				new BestPlanSelector<CarrierPlan, Carrier>());
		GenericPlanStrategyModule<CarrierPlan> vraModule = new GenericPlanStrategyModule<CarrierPlan>() {

			private boolean startCarrierReplanning = false;

			private HashMap<CarrierPlan, Integer> carrierActivityCounterMap = null;
			private ForkJoinPool forkJoinPool = null;
			private List<CarrierPlan> plansForOptimization = null;
			private List<CarrierPlan> plansForReOptimization = null;

			private VRPTransportCosts netBasedTransportCosts = null;

			@Override
			public void handlePlan(CarrierPlan carrierPlan) {
				Carrier carrier = carrierPlan.getCarrier();

				if (startCarrierReplanning) {
					if (carrier.getAttributes().getAttribute("hadFirstReplanning") == null) {
						if (plansForOptimization.size() < MAXREPLANNINGSIZE) {
							log.info("First Replanning for Carrier " + carrier.getId());

							plansForOptimization.add(carrierPlan);
							carrier.getAttributes().putAttribute("hadFirstReplanning", true);
						}

					} else {
						Random r = new Random();
						int result = r.nextInt(100 - 1) + 1;

						if (result <= 5) {
							log.info("Simulation Replanning for Carrier " + carrier.getId());
							plansForReOptimization.add(carrierPlan);

						}

					}
				}

			}

			private void createAndSolveRoutingProblem(CarrierPlan carrierPlan, double iterations, double termination) {

				Carrier carrier = carrierPlan.getCarrier();
				int serviceCount = carrier.getServices().size();

				if (!(carrier.getAttributes().getAttribute("algoRunTime") == null)) {
					double algoRunTime = (double) carrier.getAttributes().getAttribute("algoRunTime");

					if (algoRunTime > (3600 / 4)) {
						iterations = Math.ceil(iterations / 2);
						termination = Math.ceil(termination / 2);
					}

					if (termination < 2) {
						termination = 2;
					}

					if (iterations < 10) {
						iterations = 10;
					}
				}

				VehicleRoutingProblem.Builder vrpBuilder = null;

				if (isUsingZones) {
					// TODO Different modes?
					if(CarriersUtils.getCarrierMode(carrier).contains("cargobike")){						
						vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
								bikeNetwork);						
					} else {
						vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
								carNetwork);
					}
					netBasedTransportCosts = byModeVRPTransportCosts.get(CarriersUtils.getCarrierMode(carrier));
				} else {
					vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier,
							carNetwork);
				}

				vrpBuilder.setRoutingCost(netBasedTransportCosts);
				vrpBuilder.setActivityCosts(activityCosts);

				// build the problem
				VehicleRoutingProblem vrp = vrpBuilder.build();

				StateManager stateManager = new StateManager(vrp);
				ConstraintManager constraintManager = new ConstraintManager(vrp, stateManager);

				stateManager.addStateUpdater(new RouteRealStartTimeMemorizer(stateManager, vrp.getTransportCosts()));
				stateManager.updateLoadStates();
				stateManager.updateTimeWindowStates();
				stateManager.updateSkillStates();
				stateManager.addStateUpdater(new UpdateEndLocationIfRouteIsOpen());
				stateManager.addStateUpdater(new OpenRouteStateVerifier());
				stateManager.addStateUpdater(new UpdateDepartureTimeAndPracticalTimeWindows(stateManager,
						vrp.getTransportCosts(), MAXROUTEDURATION));

				constraintManager.addConstraint(
						new MaxRouteDurationConstraint(MAXROUTEDURATION, stateManager, vrp.getTransportCosts()),
						Priority.CRITICAL);
				constraintManager.addConstraint(
						new TimeWindowConstraintWithDriverTime(stateManager, vrp.getTransportCosts(), MAXROUTEDURATION),
						Priority.CRITICAL);

				constraintManager.addConstraint(new VehicleDependentTimeWindowConstraints(stateManager,
						vrp.getTransportCosts(), vrp.getActivityCosts()), ConstraintManager.Priority.HIGH);
				constraintManager.addConstraint(new ServiceDeliveriesFirstConstraint(),
						ConstraintManager.Priority.CRITICAL);

				constraintManager.addTimeWindowConstraint();
				constraintManager.addLoadConstraint();
				constraintManager.addSkillsConstraint();
				constraintManager.addConstraint(new SwitchNotFeasible(stateManager));

				double radialShare = 0.3; // standard radial share is 0.3
				double randomShare = 0.5; // standard random share is 0.5

				if (serviceCount > 250) { // if problem is huge, take only half the share for replanning
					radialShare = 0.15;
					randomShare = 0.25;
				}

				int radialServicesReplanned = Math.max(1, (int) (serviceCount * radialShare));
				int randomServicesReplanned = Math.max(1, (int) (serviceCount * randomShare));

//				if (serviceCount > 5) {
//					jspritThreads = (Runtime.getRuntime().availableProcessors());
//				}
				VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(vrp)
						.setStateAndConstraintManager(stateManager, constraintManager)
//						.setProperty(Jsprit.Parameter.THREADS, String.valueOf(jspritThreads))
						.setProperty(Jsprit.Parameter.RADIAL_MIN_SHARE, String.valueOf(radialServicesReplanned))
						.setProperty(Jsprit.Parameter.RADIAL_MAX_SHARE, String.valueOf(radialServicesReplanned))
						.setProperty(Jsprit.Parameter.RANDOM_BEST_MIN_SHARE, String.valueOf(randomServicesReplanned))
						.setProperty(Jsprit.Parameter.RANDOM_BEST_MAX_SHARE, String.valueOf(randomServicesReplanned))
						.buildAlgorithm();

//				VehicleRoutingAlgorithm algorithm = VehicleRoutingAlgorithms.readAndCreateAlgorithm(vrp,
//						ALGORITHM_FILE, stateManager  );
				algorithm.setMaxIterations((int) iterations);
				algorithm.addTerminationCriterion(new IterationWithoutImprovementTermination((int) termination));
				algorithm.getAlgorithmListeners().addListener(new StopWatch(),
						VehicleRoutingAlgorithmListeners.Priority.HIGH);

				algorithm.addListener(new DepartureTimeReScheduler());

//				algorithm.getAlgorithmListeners().addListener(new AlgorithmSearchProgressChartListener (carrier.getId().toString() + ".png"),
//						VehicleRoutingAlgorithmListeners.Priority.HIGH);

				VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
//				
				CarrierPlan plan = MatsimJspritFactory.createPlan(carrier, solution);
				NetworkRouter.routePlan(plan, netBasedTransportCosts);

				carrierPlan.getScheduledTours().clear();
				carrierPlan.getScheduledTours().addAll(plan.getScheduledTours());
			}

			@Override
			public void prepareReplanning(ReplanningContext replanningContext) {

				carrierActivityCounterMap = new HashMap<>();
				forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
				plansForOptimization = new ArrayList<>();
				plansForReOptimization = new ArrayList<>();

				if (isUsingZones) {
//					// Netbased transport costs
//					ZoneBasedTransportCosts.Builder tpCostsBuilder = ZoneBasedTransportCosts.Builder
//							.newInstance(network, vehicleTypes.getVehicleTypes().values());
//					tpCostsBuilder.setTravelTime(travelTimes);
//					tpCostsBuilder.setTimeSliceWidth(1800);
////					netBasedTransportCosts = tpCostsBuilder.build();
//					ByModeVRPTransportCosts = vrpTransportCostsFactory.createVRPTransportCostsWithModeCongestedTravelTime();

				} else {
					// Netbased transport costs
					NetworkBasedTransportCosts.Builder tpCostsBuilder = NetworkBasedTransportCosts.Builder
							.newInstance(carNetwork, vehicleTypes.getVehicleTypes().values());
					tpCostsBuilder.setTravelTime(travelTimes);
					tpCostsBuilder.setTimeSliceWidth(1800);
					netBasedTransportCosts = tpCostsBuilder.build();
				}

				if (replanningContext.getIteration() >= STARTOPTIMIZATION
						&& replanningContext.getIteration() <= STOPOPTIMIZATION) {
					startCarrierReplanning = true;
				}

			}

			@Override
			public void finishReplanning() {

				// Fill carrierActivityCounterMap -> basis for sorting the carriers by number of
				// activities before solving in parallel

				Collections.shuffle(plansForReOptimization);
				for (CarrierPlan carrierPlan : plansForReOptimization) {
					if (plansForOptimization.size() < MAXREPLANNINGSIZE) {
						plansForOptimization.add(carrierPlan);
//							createAndSolveRoutingProblem(carrierPlan, 25);
					}

				}

//				for (CarrierPlan carrierPlan : plansForOptimization) {
//					Carrier carrier = carrierPlan.getCarrier();
//
//					carrierActivityCounterMap.put(carrierPlan,
//							carrierActivityCounterMap.getOrDefault(carrierPlan, 0) + carrier.getServices().size());
//					carrierActivityCounterMap.put(carrierPlan,
//							carrierActivityCounterMap.getOrDefault(carrierPlan, 0) + carrier.getShipments().size());
//				}
//
//				HashMap<CarrierPlan, Integer> sortedMap = carrierActivityCounterMap.entrySet().stream()
//						.sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).collect(Collectors
//								.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
//
//				ArrayList<CarrierPlan> tempList = new ArrayList<>(sortedMap.keySet());
				
				List<CarrierPlan> tempList  = plansForOptimization;

				AtomicInteger progress = new AtomicInteger();

				try {
					forkJoinPool.submit(() -> tempList.parallelStream().forEach(carrierPlan -> {

						log.info("ROUTING CARRIER " + progress.incrementAndGet() + " OUT OF " + tempList.size()
								+ " TOTAL CARRIERS");

						double start = System.currentTimeMillis();
						Carrier carrier = carrierPlan.getCarrier();
						int serviceCount = carrier.getServices().size();
//						if (serviceCount > 250) {
//							createAndSolveRoutingProblem(carrierPlan, 10, 3);
//						} else {
//							createAndSolveRoutingProblem(carrierPlan, 20, 5);
//						}
						if (serviceCount > 250) {
							createAndSolveRoutingProblem(carrierPlan, 20, 3);
						} else {
							createAndSolveRoutingProblem(carrierPlan, 40, 5);
						}
						double algoRunTime = (System.currentTimeMillis() - start) / 1000;
						log.info(
								"routing for carrier " + carrier.getId() + " finished. Tour planning plus routing took "
										+ (System.currentTimeMillis() - start) / 1000 + " seconds." + " Carrier has "
										+ serviceCount + " services");
						carrier.getAttributes().putAttribute("algoRunTime", algoRunTime);

//						SolutionPrinter.print(vrp, solution, Print.VERBOSE);

					})).get();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					throw new RuntimeException();
				
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					throw new RuntimeException();
				}
				forkJoinPool.shutdown();
			}

		};

		replanningStrat.addStrategyModule(vraModule);
		return replanningStrat;
	}

}
