/*******************************************************************************
 * Copyright (c) 2011 Stefan Schroeder.
 * eMail: stefan.schroeder@kit.edu
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     Stefan Schroeder - initial API and implementation
 ******************************************************************************/
package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.Counter;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.jsprit.FiFoTravelTime;
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.vehicles.VehicleType;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy MATSim/jsprit adapter for live travel times, time bins and exact endpoints.
 * Paths start after the origin link and include the destination link.
 *
 * <p>Generation cache and worker ownership have separate components.
 * Public builder, native counters, listeners and vehicle wrapper remain compatibility APIs.
 * New frozen-context consumers use {@link ModalTransportCosts}.
 */
@SuppressWarnings("deprecation")
public class ZoneBasedTransportCosts implements ReplanningTransportCosts {

	private static final Logger LOGGER = LogManager.getLogger(ZoneBasedTransportCosts.class);

	private final Map<String, Id<Link>> idCache = new ConcurrentHashMap<>();

	public interface InternalLeastCostPathCalculatorListener {

		void startCalculation(long routerId);

		void endCalculation(long routerId);

	}

    /** Historical nested type retained for native callers. */
    public static class MatsimVehicleWrapper extends LegacyRoutingVehicle {
        public MatsimVehicleWrapper(Vehicle vehicle){super(vehicle,"car");}
        private MatsimVehicleWrapper(Vehicle vehicle,String mode){super(vehicle,mode);}
        public MatsimVehicleWrapper(CarrierVehicle vehicle){super(vehicle);}
    }
	/**
	 * The key to cache {@link TransportData}.
	 *
	 * <p>
	 * Two TransportDataKeys are equal if the following data are equal</br>
	 * - from-locations</br>
	 * - to-locations</br>
	 * - time and</br>
	 * - vehicleTypes
	 *
	 * <p>
	 * The time-value is usually a representative value for a certain time-bin. If
	 * the time-bin's width is 1, it calculates and caches each and every
	 * transport-data which might be a) very time-consuming and b) very
	 * memory-consuming.
	 *
	 * @author stefan schröder
	 *
	 */
	private record TransportDataKey(Id<Link> from, Id<Link> to, double time,
			String vehicleType, double maximumVelocity) {}

	private record VehicleKey(String id, String type, double maximumVelocity) {}

	/**
	 * Stores transport-costs, transport-times and the distance of travel.
	 *
	 * @author stefan schröder
	 *
	 */
	static class TransportData {
		public final double transportCosts;
		public final double transportTime;
		public final double transportDistance;

		public TransportData(double transportCosts, double transportTime, double transportDistance) {
			super();
			this.transportCosts = transportCosts;
			this.transportTime = transportTime;
			this.transportDistance = transportDistance;
		}

	}

    /** Compatibility configuration boundary; resolved new callers use the central HAGRID configuration. */
    public static class Builder {
        private final Network network;
        private final Map<String,String> vehicleNetworkModes=new HashMap<>();
        private final Map<String,VehicleRoutingDisutility.VariableCosts> typeSpecificCosts=new HashMap<>();
        private final String defaultTypeId=UUID.randomUUID().toString();

        // Travel-time and objective policy.
        private TravelTime travelTime=freeSpeedTravelTime();
        private TravelDisutility baseDisutility;
        private int timeSliceWidth=Integer.MAX_VALUE;
        private boolean isFIFO;


        // Memory and worker reuse limits.
        private int maximumCacheEntries=1_000_000;
        private int routerPoolSize=Math.max(2,Runtime.getRuntime().availableProcessors());
        private LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;

        // Optional legacy road pricing.
        private boolean withToll;
        private VehicleTypeDependentRoadPricingCalculator roadPricingCalculator=new VehicleTypeDependentRoadPricingCalculator();

        public static Builder newInstance(Network network,Collection<VehicleType> types){return new Builder(network,types);}
        public static Builder newInstance(Network network){return new Builder(network,Collections.emptyList());}

        private Builder(Network network,Collection<VehicleType> types) {
            this.network=Objects.requireNonNull(network,"network");
            for(var type:types) {
                vehicleNetworkModes.put(type.getId().toString(),type.hasNetworkMode()?type.getNetworkMode():"car");
                typeSpecificCosts.put(type.getId().toString(),new VehicleRoutingDisutility.VariableCosts(
                        type.getCostInformation().getCostsPerMeter(),type.getCostInformation().getCostsPerSecond()));
            }
            typeSpecificCosts.put(defaultTypeId,new VehicleRoutingDisutility.VariableCosts(1,0));
        }

        /** Live link freespeed at entry time, capped by the vehicle's maximum speed. */
        private static TravelTime freeSpeedTravelTime() {
            return (link,time,person,vehicle)-> {
                double speed=vehicle.getType().getMaximumVelocity()<link.getFreespeed(time)
                        ?vehicle.getType().getMaximumVelocity():link.getFreespeed(time);
                if(speed<=0)throw new IllegalStateException("velocity must be bigger than zero");
                String mode;
                try {mode=vehicle.getType().getNetworkMode();}
                catch(NullPointerException missingMode){mode="car";}
                // Historical compatibility penalty; modal graph filtering is owned by the factory.
                return link.getLength()/(link.getAllowedModes().contains(mode)?speed:0.001);
            };
        }

        public Builder setTravelTime(TravelTime time){travelTime=Objects.requireNonNull(time,"travelTime");return this;}
        public Builder setBaseTravelTimeAndDisutility(TravelTime time,TravelDisutility costs) {
            travelTime=time;baseDisutility=costs;return this;
        }
        /** Quantization evaluates queries at the start of each legacy time bin. */
        public Builder setTimeSliceWidth(int seconds) {
            if(seconds<=0)throw new IllegalArgumentException("Time slice width must be positive");
            timeSliceWidth=seconds;return this;
        }
        /** Selects the native legacy FIFO wrapper; frozen-context certification remains separate. */
        public Builder setFIFO(boolean enabled){isFIFO=enabled;return this;}

        /** Supplied factories must support concurrent calculator creation. */
        public Builder setThreadSafeLeastCostPathCalculatorFactory(LeastCostPathCalculatorFactory factory) {
            leastCostPathCalculatorFactory=Objects.requireNonNull(factory,"factory");return this;
        }
        /** Legacy reuse limit; native direct-router fallback is still per thread. */
        public Builder setRouterPoolSize(int count) {
            if(count<=0)throw new IllegalArgumentException("routerPoolSize must be positive");
            routerPoolSize=count;return this;
        }
        public Builder setMaximumCacheEntries(int count) {
            if(count<=0)throw new IllegalArgumentException("Maximum cache entries must be positive");
            maximumCacheEntries=count;return this;
        }
        public Builder enableZoneBasedCaching(boolean enabled){hagrid.core.config.ResolvedHagridConfig.rejectRemoved("routing.zoneBasedCachingEnabled",enabled);return this;}
        public Builder setZoneBasedEuclideanThreshold(double meters) {
            if(!Double.isFinite(meters)||meters<0)
                throw new IllegalArgumentException("Zone-based Euclidean threshold must be non-negative");
            return this;
        }
        /** Historical call retained with its explicit ignored-setting diagnostic. */
        public Builder enableZoneWarmup(boolean enabled,double departureTimeSeconds) {
            LOGGER.warn("Zone warmup support has been removed and will be ignored (enabled={}, departureTimeSeconds={}).",
                    enabled,departureTimeSeconds);return this;
        }
        public Builder setRoadPricingCalculator(VehicleTypeDependentRoadPricingCalculator calculator) {
            withToll=true;roadPricingCalculator=calculator;return this;
        }
        /** Updates variable rates for subsequent builds; fixed costs belong to the solver objective. */
        public void addVehicleTypeSpecificCosts(String typeId,double fix,double perSecond,double perMeter) {
            typeSpecificCosts.put(typeId,new VehicleRoutingDisutility.VariableCosts(perMeter,perSecond));
        }
        public ZoneBasedTransportCosts build() {
            TravelTime effectiveTime=isFIFO?new FiFoTravelTime(travelTime,timeSliceWidth):travelTime;
            TravelDisutility effectiveCosts=baseDisutility==null
                    ?new VehicleRoutingDisutility(typeSpecificCosts,effectiveTime):baseDisutility;
            if(withToll)effectiveCosts=new TollRoutingDisutility(effectiveCosts,roadPricingCalculator);
            return new ZoneBasedTransportCosts(this,effectiveTime,effectiveCosts);
        }
    }
	private final Network network;

	private final GenerationTransportCache<TransportDataKey, TransportData> cache;
	private final LegacyRoutingWorkers routingWorkers;
	private final Map<String, String> vehicleNetworkModes;

	private final Vehicle defaultVehicle;
	private static final TransportData ZERO = new TransportData(0, 0, 0);
	private static final TransportData UNREACHABLE = new TransportData(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

	private final TravelDisutility travelDisutility;

	private final TravelTime travelTime;

	/**
	 * the width of the time-bin
	 */
	private final int timeSliceWidth;

	private final Map<VehicleKey, org.matsim.vehicles.Vehicle> matsimVehicles = new ConcurrentHashMap<>();

	private final VehicleTypeDependentRoadPricingCalculator roadPricingCalc;

	/**
	 * by default sets the {@link SpeedyALTFactory}
	 */
	private final LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;

	private final Collection<InternalLeastCostPathCalculatorListener> listeners = new ArrayList<InternalLeastCostPathCalculatorListener>();

	private final String defaultTypeId;

	public final Counter cacheRequestedCounter = new Counter("#ZoneTransportCostRequests ");

	public final Counter cacheHitCounter = new Counter("#ZoneTransportCostCacheHits ");

	public final Counter cacheMemorizedCounter = new Counter("#ZoneTransportCostValues cached ");

	private ZoneBasedTransportCosts(Builder builder, TravelTime effectiveTime, TravelDisutility effectiveDisutility) {
		this.travelDisutility = effectiveDisutility;
		this.travelTime = effectiveTime;
		this.network = builder.network;
		this.leastCostPathCalculatorFactory = builder.leastCostPathCalculatorFactory == null
				? defaultRouterFactory() : builder.leastCostPathCalculatorFactory;
		this.roadPricingCalc = builder.roadPricingCalculator;
		this.timeSliceWidth = builder.timeSliceWidth;
		this.defaultTypeId = builder.defaultTypeId;
		this.routingWorkers = new LegacyRoutingWorkers(builder.routerPoolSize, this::createRouter);
		this.cache = new GenerationTransportCache<>(builder.maximumCacheEntries,
				cacheRequestedCounter::incCounter, cacheHitCounter::incCounter, cacheMemorizedCounter::incCounter);
		this.vehicleNetworkModes = Map.copyOf(builder.vehicleNetworkModes);
		this.defaultVehicle = VehicleImpl.Builder.newInstance("default").setType(
				com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.Builder.newInstance(defaultTypeId).build())
				.setStartLocation(Location.newInstance("default")).build();
	}

	private static LeastCostPathCalculatorFactory defaultRouterFactory() {
		return new LeastCostPathCalculatorFactory() {
			private final SpeedyALTFactory factory = new SpeedyALTFactory();
			@Override
			public synchronized LeastCostPathCalculator createPathCalculator(Network network,
					TravelDisutility costs, TravelTime times) {
				return factory.createPathCalculator(network, costs, times);
			}
		};
	}

	/**
	 * Gets the transport-time.
	 *
	 * <p>
	 * If <code>fromId.equals(toId)</code> it returns 0.0. Otherwise, it looks up in
	 * the cache whether the transport-time has already been computed (see
	 * {@link TransportDataKey}, {@link TransportData}). If so, it returns the
	 * cached travel-time. If not, it computes and caches new values with the
	 * leastCostPathCalc defined in here.
	 *
	 * @Throws {@link IllegalStateException} if vehicle is null
	 */
	@Override
	public double getTransportTime(Location fromId, Location toId, double departureTime, Driver driver,
			Vehicle vehicle) {
		return getTransportData(fromId, toId, departureTime, vehicle).transportTime;
	}

	private void informEndCalc() {
		long threadId = Thread.currentThread().threadId();
		for (InternalLeastCostPathCalculatorListener l : listeners)
			l.endCalculation(threadId);
	}

	private void informStartCalc() {
		long threadId = Thread.currentThread().threadId();
		for (InternalLeastCostPathCalculatorListener l : listeners)
			l.startCalculation(threadId);
	}

	/**
	 * Gets the transport-costs.
	 *
	 * <p>
	 * If <code>fromId.equals(toId)</code> it returns 0.0. Otherwise, it looks up in
	 * the cache whether the transport-costs have already been computed (see
	 * {@link TransportDataKey}, {@link TransportData}). If so, it returns the
	 * cached travel-cost value. If not, it computes and caches new values with the
	 * leastCostPathCalc defined in here.
	 *
	 * @Throws {@link IllegalStateException} if vehicle is null
	 */
	@Override
	public double getTransportCost(Location fromId, Location toId, double departureTime, Driver driver,
			Vehicle vehicle) {
		return getTransportData(fromId, toId, departureTime, vehicle).transportCosts;
	}


	/**
	 * Gets the distance for the transport.
	 *
	 * <p>
	 * If <code>fromId.equals(toId)</code> it returns 0.0. Otherwise, it looks up in
	 * the cache whether the transport-distance has already been computed (see
	 * {@link TransportDataKey}, {@link TransportData}). If so, it returns the
	 * cached distance. If not, it computes and caches new values with the
	 * leastCostPathCalc defined in here.
	 *
	 * @Throws {@link IllegalStateException} if vehicle is null
	 */
	@Override
	public double getDistance(Location fromId, Location toId, double departureTime, Vehicle vehicle) {
		return getTransportData(fromId, toId, departureTime, vehicle).transportDistance;
	}

	private TransportData getTransportData(Location from, Location to, double departureTime, Vehicle suppliedVehicle) {
		if (from == to || Objects.equals(from.getId(), to.getId())) return ZERO;
		if (!Double.isFinite(departureTime)) throw new IllegalArgumentException("Departure time must be finite");
		Vehicle vehicle = suppliedVehicle == null ? defaultVehicle : suppliedVehicle;
		double binStart = Math.floor(Math.max(0, departureTime) / timeSliceWidth) * timeSliceWidth;
		Link fromLink = network.getLinks().get(getLinkId(from.getId()));
		Link toLink = network.getLinks().get(getLinkId(to.getId()));
		if (fromLink == null || toLink == null) return UNREACHABLE;
		TransportDataKey key = new TransportDataKey(fromLink.getId(), toLink.getId(), binStart,
				vehicle.getType().getTypeId(), vehicle.getType().getMaxVelocity());
        Link origin=fromLink, destination=toLink;
        return cache.get(key,()-> {
            informStartCalc();
            try {return calculateTransportData(binStart,vehicle,origin,destination);}
            finally {informEndCalc();}
        });
    }
	/** Begin a new cache generation after travel times change; earlier requests cannot populate it. */
	public void clearCache() { cache.clear(); }

    @Override public void prepareForReplanning(){clearCache();}
    @Override public void logSummary(String label){logCacheSummary(label);}

	/**
	 * @return the listeners
	 */
	public Collection<InternalLeastCostPathCalculatorListener> getInternalListeners() {
		return listeners;
	}

	/**
	 * Backward transport-costs are approximated by calculating
	 * <code>getTransportCost(fromId, toId, arrivalTime, driver, vehicle);</code>.
	 *
	 * <p>
	 * This is a rather bad approximation. If you require this, you should implement
	 * another {@link VehicleRoutingTransportCosts}
	 *
	 * @Throws {@link IllegalStateException} if vehicle is null
	 */
	@Override
	public double getBackwardTransportCost(Location fromId, Location toId, double arrivalTime, Driver driver,
			Vehicle vehicle) {
		return getTransportCost(fromId, toId, arrivalTime, driver, vehicle);
	}

	/**
	 * Backward transport-time are approximated by calculating
	 * <code>getTransportTime(fromId, toId, arrivalTime, driver, vehicle);</code>.
	 *
	 * <p>
	 * This is a rather bad approximation. If you require this, you should implement
	 * another {@link VehicleRoutingTransportCosts}.
	 *
	 * @Throws {@link IllegalStateException} if vehicle is null
	 */
	@Override
	public double getBackwardTransportTime(Location fromId, Location toId, double arrivalTime, Driver driver,
			Vehicle vehicle) {
		return getTransportTime(fromId, toId, arrivalTime, driver, vehicle);
	}

	private org.matsim.vehicles.Vehicle getMatsimVehicle(Vehicle vehicle) {
		VehicleKey key = new VehicleKey(vehicle.getId(), vehicle.getType().getTypeId(), vehicle.getType().getMaxVelocity());
		return matsimVehicles.computeIfAbsent(key, k -> new MatsimVehicleWrapper(vehicle,
				vehicleNetworkModes.getOrDefault(k.type(), "car")));
	}

	private Id<Link> getLinkId(String id) {
		return idCache.computeIfAbsent(id, s -> Id.create(s, Link.class));
	}

	@Override
	public LeastCostPathCalculator getRouter() { return routingWorkers.getThreadRouter(); }

	private LeastCostPathCalculator createRouter() {
		return leastCostPathCalculatorFactory.createPathCalculator(network, travelDisutility, travelTime);
	}

	/**
	 * Gets the network the calculation is based on.
	 *
	 * @return
	 */
	public Network getNetwork() {
		return network;
	}

	/**
	 * Gets the travel-time.
	 *
	 * @return {@link TravelTime}
	 */
	public TravelTime getTravelTime() {
		return travelTime;
	}

	/**
	 * Gets the {@link VehicleTypeDependentRoadPricingCalculator}
	 *
	 * @return {@link VehicleTypeDependentRoadPricingCalculator}
	 */
	public VehicleTypeDependentRoadPricingCalculator getRoadPricingCalculator() {
		return roadPricingCalc;
	}

    private TransportData calculateTransportData(double departureTime,Vehicle vehicle,Link fromLink,Link toLink) {
        var matsimVehicle=getMatsimVehicle(vehicle);
        return routingWorkers.use(router-> {
            Path path=router.calcLeastCostPath(fromLink.getToNode(),toLink.getFromNode(),departureTime,null,matsimVehicle);
            if(path==null)return UNREACHABLE;
            double arrival=departureTime+path.travelTime;
            double costTo=travelDisutility.getLinkTravelDisutility(toLink,arrival,null,matsimVehicle);
            double timeTo=travelTime.getLinkTravelTime(toLink,arrival,null,matsimVehicle);
            double distance=toLink.getLength();
            for(Link link:path.links)distance+=link.getLength();
            return new TransportData(path.travelCost+costTo,path.travelTime+timeTo,distance);
        });
    }
    public CacheStatsSnapshot snapshotAndResetSummaryWindow() {
        var stats=cache.statistics();
        return new CacheStatsSnapshot(stats.uniqueEntries(),stats.totalRequests(),stats.totalHits(),stats.totalMemoized(),
                stats.periodRequests(),stats.periodHits(),stats.periodMemoized());
    }
	public synchronized void logCacheSummary(String contextLabel) {
		CacheStatsSnapshot snapshot = snapshotAndResetSummaryWindow();

		String totalHitRate = toPercent(snapshot.totalHits(), snapshot.totalRequests());
		String periodHitRate = toPercent(snapshot.periodHits(), snapshot.periodRequests());
		long periodMisses = Math.max(0L, snapshot.periodRequests() - snapshot.periodHits());
		long totalMisses = Math.max(0L, snapshot.totalRequests() - snapshot.totalHits());

		LOGGER.info(
				"[ZoneCostCache] {} | uniqueKeys={} | total: {} req, {} hits, {} misses (hit rate {}) | period: {} req, {} hits, {} misses (hit rate {}) | memoized+: {} (total {})",
				contextLabel,
				snapshot.uniqueEntries(),
				snapshot.totalRequests(),
				snapshot.totalHits(),
				totalMisses,
				totalHitRate,
				snapshot.periodRequests(),
				snapshot.periodHits(),
				periodMisses,
				periodHitRate,
				snapshot.periodMemoized(),
				snapshot.totalMemoized());
	}

	private static String toPercent(long hits, long requests) {
		if (requests <= 0L) {
			return "n/a";
		}
		double rate = (hits * 100.0d) / requests;
		return String.format(Locale.ENGLISH, "%.2f%%", rate);
	}

	public static record CacheStatsSnapshot(
			long uniqueEntries,
			long totalRequests,
			long totalHits,
			long totalMemoized,
			long periodRequests,
			long periodHits,
			long periodMemoized) {
	}

}
