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
package hagrid.utils.routing;

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
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This calculates transport-times, transport-costs and the distance to cover
 * the distance from one location to another. It calculates these values based
 * on a {@link Network} to serve as {@link VehicleRoutingTransportCosts} in the
 * {@link VehicleRoutingProblem}. The distance includes the links of the path
 * and the fromLink and not the toLink.
 *
 * <p>
 * It can be used with multiple threads. Note that each thread gets its own
 * leastCostPathCalculator. It is created only once and cached afterwards. Thus
 * it requires a threadSafe leastCostPathCalculatorFactory (the calculator
 * itself does not need to be thread-safe).
 *
 * <p>
 * If the client of this class requests cost-information to get from
 * from-location to to-location at a certain time and with a certain vehicle, it
 * looks up whether there is already an entry in the cache. If so, it returns
 * the cached value, if not it calculates new values with a
 * leastCostPathCalculator defined in here. It looks up the cached values
 * according to the {@link TransportDataKey}.
 *
 * <p>
 * Two TransportDataKeys are equal if the following data are equal</br>
 * - from-locations</br>
 * - to-locations</br>
 * - time and</br>
 * - vehicleTypes
 *
 * <p>
 * Keep in mind that if you have many locations, small time-bins and many
 * vehicleTypes, calculations get very time- and memory-consuming.
 *
 * @author stefan schröder
 *
 */
public class ZoneBasedTransportCosts implements VRPTransportCosts {

	public interface InternalLeastCostPathCalculatorListener {

		void startCalculation(long routerId);

		void endCalculation(long routerId);

	}

	/**
	 * This creates a matsim-vehicle {@link org.matsim.vehicles.Vehicle} from a
	 * matsim-freight-vehicle {@link CarrierVehicle} and jsprit-vehicle .
	 *
	 * @author stefan schröder
	 *
	 */
	public static class MatsimVehicleWrapper implements org.matsim.vehicles.Vehicle {

		private final Id<org.matsim.vehicles.Vehicle> id;

		private final org.matsim.vehicles.VehicleType type;

		private final Attributes attributes = new AttributesImpl();

		public MatsimVehicleWrapper(com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
			this.id = Id.create(vehicle.getId(), org.matsim.vehicles.Vehicle.class);
			this.type = makeType(vehicle.getType().getTypeId(), vehicle.getType().getMaxVelocity());
		}

		public MatsimVehicleWrapper(CarrierVehicle vehicle) {
			this.id = vehicle.getId();
			this.type = vehicle.getType();
		}

		private org.matsim.vehicles.VehicleType makeType(String typeId, double maxVelocity) {
			org.matsim.vehicles.VehicleType vehicleTypeImpl = VehicleUtils
					.createVehicleType(Id.create(typeId, VehicleType.class));
			vehicleTypeImpl.setMaximumVelocity(maxVelocity);
			return vehicleTypeImpl;
		}

		@Override
		public Id<org.matsim.vehicles.Vehicle> getId() {
			return id;
		}

		@Override
		public org.matsim.vehicles.VehicleType getType() {
			return type;
		}

		@Override
		public Attributes getAttributes() { return this.attributes; }
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
	static class TransportDataKey {
		private final String from;
		private final String to;
		private final double time;
		private final String vehicleType;

		public TransportDataKey(String from, String to, double time, String vehicleType) {
			super();
			this.from = from;
			this.to = to;
			this.time = time;
			this.vehicleType = vehicleType;
		}

		public String getFrom() {
			return from;
		}

		public String getTo() {
			return to;
		}

		public double getTime() {
			return time;
		}

		public String getVehicleType() {
			return vehicleType;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((from == null) ? 0 : from.hashCode());
			long temp;
			temp = Double.doubleToLongBits(time);
			result = prime * result + (int) (temp ^ (temp >>> 32));
			result = prime * result + ((to == null) ? 0 : to.hashCode());
			result = prime * result + ((vehicleType == null) ? 0 : vehicleType.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TransportDataKey other = (TransportDataKey) obj;
			if (from == null) {
				if (other.from != null)
					return false;
			} else if (!from.equals(other.from))
				return false;
			if (Double.doubleToLongBits(time) != Double.doubleToLongBits(other.time))
				return false;
			if (to == null) {
				if (other.to != null)
					return false;
			} else if (!to.equals(other.to))
				return false;
			if (vehicleType == null) {
                return other.vehicleType == null;
			} else return vehicleType.equals(other.vehicleType);
        }

	}

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

	/**
	 * Calculates vehicle-type-dependent travelDisutility per link.
	 *
	 * <p>
	 * Note that it uses
	 * <code>vehicleType.getCostInformation().perDistanceUnit</code> and
	 * <code>vehicleType.getCostInformation().perTimeUnit</code> to calculate time
	 * and distance related transport costs.
	 *
	 * @author stefan
	 *
	 */
	static class BaseVehicleTransportCosts implements TravelDisutility {

		private final TravelTime travelTime;

		private Map<String, VehicleTypeVarCosts> typeSpecificCosts = new HashMap<String, ZoneBasedTransportCosts.VehicleTypeVarCosts>();

		/**
		 * Constructs travelDisutility according to the builder.
		 *
		 */
		private BaseVehicleTransportCosts(Map<String, VehicleTypeVarCosts> typeSpecificCosts, TravelTime travelTime) {
			this.travelTime = travelTime;
			this.typeSpecificCosts = typeSpecificCosts;
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person,
				org.matsim.vehicles.Vehicle vehicle) {
			VehicleTypeVarCosts typeCosts = typeSpecificCosts.get(vehicle.getType().getId().toString());
			if (typeCosts == null)
				throw new IllegalStateException(
						"type specific costs for " + vehicle.getType().getId().toString() + " are missing.");
			double tt = travelTime.getLinkTravelTime(link, time, person, vehicle);
			return typeCosts.perMeter * link.getLength() + typeCosts.perSecond * tt;
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			double minDisutility = Double.MAX_VALUE;
			double free_tt = link.getLength() / link.getFreespeed();
			for (VehicleTypeVarCosts c : typeSpecificCosts.values()) {
				double disu = c.perMeter * link.getLength() + c.perSecond * free_tt;
				if (disu < minDisutility)
					minDisutility = disu;
			}
			return minDisutility;
		}

	}

	private static class VehicleTypeVarCosts {
		final double perMeter;
		final double perSecond;

		VehicleTypeVarCosts(double perMeter, double perSecond) {
			super();
			this.perMeter = perMeter;
			this.perSecond = perSecond;
		}
	}

	/**
	 * Calculates disutilites including toll.
	 *
	 * @author stefan
	 *
	 */
	static class VehicleTransportCostsIncludingToll implements TravelDisutility {

//		private static Logger logger = Logger.getLogger(VehicleTransportCostsIncludingToll.class);

		private final TravelDisutility baseTransportDisutility;

		private final VehicleTypeDependentRoadPricingCalculator vehicleTypeDependentPricingCalculator;

		public VehicleTransportCostsIncludingToll(TravelDisutility baseTransportDisutility,
				VehicleTypeDependentRoadPricingCalculator vehicleTypeDependentPricingCalculator) {
			super();
			this.baseTransportDisutility = baseTransportDisutility;
			this.vehicleTypeDependentPricingCalculator = vehicleTypeDependentPricingCalculator;
//			System.out.println("huuuuuuuuuuuuuuuuuuuu - initialize transport costs with toll");
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person,
				org.matsim.vehicles.Vehicle vehicle) {
			double costs = baseTransportDisutility.getLinkTravelDisutility(link, time, person, vehicle);
			Id<org.matsim.vehicles.VehicleType> typeId = vehicle.getType().getId();
			double toll = vehicleTypeDependentPricingCalculator.getTollAmount(typeId, link, time);
//			System.out.println("huuuuuuuuuuuuuuuuuuuu - paid toll");
			return costs + toll;
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return baseTransportDisutility.getLinkMinimumTravelDisutility(link);
		}

	}

	public static class Builder {

		public static Builder newInstance(Network network, Collection<VehicleType> vehicleTypes) {
			return new Builder(network, vehicleTypes);
		}

		public static Builder newInstance(Network network) {
			return new Builder(network, Collections.emptyList());
		}

		/**
		 * By default it takes <code>link.getFreespeed(time);</code> to calculate the
		 * travelTime over that link.
		 */
		private TravelTime travelTime = new TravelTime() {

			@Override
			public double getLinkTravelTime(Link link, double time, Person person,
					org.matsim.vehicles.Vehicle vehicle) {
				double velocity;
				if (vehicle.getType().getMaximumVelocity() < link.getFreespeed(time)) {
					velocity = vehicle.getType().getMaximumVelocity();
				} else
					velocity = link.getFreespeed(time);
				if (velocity <= 0.0)
					throw new IllegalStateException("velocity must be bigger than zero");
				
				String mode = vehicle.getType().getNetworkMode();
				
		        if(link.getAllowedModes().contains(mode)){
		            return link.getLength()/Math.min(velocity, link.getFreespeed());
		        } else {
		            return link.getLength()/0.001;
		        }
				
//				return link.getLength() / velocity;
			}
		};

		private TravelDisutility baseDisutility;

		private TravelDisutility finalDisutility;

		private int timeSliceWidth = Integer.MAX_VALUE;

		private LeastCostPathCalculatorFactory leastCostPathCalculatorFactory = new LeastCostPathCalculatorFactory() {

			@Override
			public LeastCostPathCalculator createPathCalculator(Network network, TravelDisutility travelCosts,
					TravelTime travelTimes) {
				return  new SpeedyALTFactory().createPathCalculator(network, travelCosts, travelTime);
			}
		};

		private VehicleTypeDependentRoadPricingCalculator roadPricingCalculator = new VehicleTypeDependentRoadPricingCalculator();

		private boolean withToll = false;

		private final Network network;

		private final Map<String, VehicleTypeVarCosts> typeSpecificCosts = new HashMap<String, ZoneBasedTransportCosts.VehicleTypeVarCosts>();

		private boolean isFIFO = false;

		private final String defaultTypeId = UUID.randomUUID().toString();

		/**
		 * Creates the builder requiring {@link Network} and a collection of
		 * {@link VehicleType}.
		 *
		 * @param network
		 * @param vehicleTypes must be all vehicleTypes and their assigned
		 *                     costInformation in the system.
		 */
		private Builder(Network network, Collection<VehicleType> vehicleTypes) {
			this.network = network;
			retrieveTypeSpecificCosts(vehicleTypes);
		}

		private void retrieveTypeSpecificCosts(Collection<VehicleType> vehicleTypes) {
			for (VehicleType type : vehicleTypes) {
				typeSpecificCosts.put(type.getId().toString(), new VehicleTypeVarCosts(
						type.getCostInformation().getCostsPerMeter(), type.getCostInformation().getCostsPerSecond()));
			}
			typeSpecificCosts.put(defaultTypeId, new VehicleTypeVarCosts(1., 0.));
		}

		/**
		 * Sets the travelTime. By default travelTime is based on
		 * <code>link.getFreespeed();</code>.
		 *
		 * @param travelTime
		 * @return this builder
		 */
		public Builder setTravelTime(TravelTime travelTime) {
			this.travelTime = travelTime;
			return this;
		}

		/**
		 * Sets travelTime and travelDisutility.
		 *
		 */
		public Builder setBaseTravelTimeAndDisutility(TravelTime travelTime, TravelDisutility travelDisutility) {
			this.travelTime = travelTime;
			this.baseDisutility = travelDisutility;
			return this;
		}

		/**
		 * Sets the width of the time-bin. By default it is Integer.MAX_VALUE().
		 * <p>
		 * </p>
		 * <i>Note that this needs to be set to some plausible value to enable any kind
		 * of time-dependent network!!!</i> In particular if you are using the matsim
		 * time dependent network option.
		 */
		public Builder setTimeSliceWidth(int timeSliceWidth) {
			this.timeSliceWidth = timeSliceWidth;
			return this;
		}

		/**
		 * Ensures FIFO. ! NOT YET ENABLED.
		 */
		public Builder setFIFO(boolean isFIFO) {
			this.isFIFO = isFIFO;
			return this;
		}

		/**
		 * Sets the leastCostPathCalculatorFactory to create the calculator to calculate
		 * networkPaths, travelTimes and transportCosts.
		 * <p>
		 * NOTE, make sure the leastCostPathCalculatorFactory is threadSafe, since for
		 * each thread a new LCPA is created with the same LCPA-factory. That is,
		 * memorizing data in the factory-obj might violate thread-safety.
		 * <p>
		 * By default it use {@link FastDijkstraFactory}
		 *
		 * @param {@link {@link LeastCostPathCalculatorFactory}
		 * @return this builder
		 */
		public Builder setThreadSafeLeastCostPathCalculatorFactory(
				LeastCostPathCalculatorFactory leastCostPathCalcFactory) {
			this.leastCostPathCalculatorFactory = leastCostPathCalcFactory;
			return this;
		}

		public Builder setRoadPricingCalculator(VehicleTypeDependentRoadPricingCalculator calculator) {
			withToll = true;
			this.roadPricingCalculator = calculator;
			return this;
		}

		/**
		 * Builds the network-based transport costs which are the basis for solving the
		 * {@link VehicleRoutingProblem}.
		 * <p>
		 * </p>
		 * Comments:
		 * <ul>
		 * <li>By default this will take free speed travel times.
		 * <li>yyyy These free speed travel times do <i>not</i> take the time-dependent
		 * network into account. kai, jan'14
		 * <li>Either can be changed with builder.setTravelTime(...) or with
		 * builder.setBaseTravelTimeAndDisutility(...).
		 * </ul>
		 *
		 * @return
		 */
		public ZoneBasedTransportCosts build() {
			if (baseDisutility == null) {
				if (isFIFO)
					travelTime = new FiFoTravelTime(travelTime, timeSliceWidth);
				baseDisutility = new BaseVehicleTransportCosts(typeSpecificCosts, travelTime);
			}
			if (withToll) {
				finalDisutility = new VehicleTransportCostsIncludingToll(baseDisutility, roadPricingCalculator);
			} else
				finalDisutility = baseDisutility;
			return new ZoneBasedTransportCosts(this);
		}

		/**
		 * Adds type-specific costs. If typeId already exists, existing entry is
		 * overwritten.
		 *
		 * @param typeId
		 * @param fix
		 * @param perSecond
		 * @param perMeter
		 */
		public void addVehicleTypeSpecificCosts(String typeId, double fix, double perSecond, double perMeter) {
			typeSpecificCosts.put(typeId, new VehicleTypeVarCosts(perMeter, perSecond));
		}

	}

	private final Network network;

	/**
	 * cost-cache to cache transport-costs and transport-times (see
	 * {@link TransportData}) according to {@link TransportDataKey}
	 */
	private final ConcurrentHashMap<TransportDataKey, TransportData> costCache = new ConcurrentHashMap<TransportDataKey, TransportData>();

	/**
	 * caches leastCostPathCalculators according to
	 * <code>Thread.currentThread().getId()</code>
	 */
	private final ConcurrentHashMap<Long, LeastCostPathCalculator> routerCache = new ConcurrentHashMap<Long, LeastCostPathCalculator>();

	private final TravelDisutility travelDisutility;

	private final TravelTime travelTime;

	/**
	 * the width of the time-bin
	 */
	private final int timeSliceWidth;

//	public final Counter ttMemorizedCounter;

//	public final Counter ttRequestedCounter;

	private final Map<String, org.matsim.vehicles.Vehicle> matsimVehicles = new HashMap<String, org.matsim.vehicles.Vehicle>();

	private final VehicleTypeDependentRoadPricingCalculator roadPricingCalc;

	/**
	 * by default sets the {@link FastDijkstraFactory}
	 */
	private final LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;

	private final Collection<InternalLeastCostPathCalculatorListener> listeners = new ArrayList<InternalLeastCostPathCalculatorListener>();

	private final String defaultTypeId;

	private ZoneBasedTransportCosts(Builder builder) {
		super();
		this.travelDisutility = builder.finalDisutility;
		this.travelTime = builder.travelTime;
		this.network = builder.network;
		this.leastCostPathCalculatorFactory = builder.leastCostPathCalculatorFactory;
		this.roadPricingCalc = builder.roadPricingCalculator;
		this.timeSliceWidth = builder.timeSliceWidth;
		this.defaultTypeId = builder.defaultTypeId;
//		this.ttMemorizedCounter = new Counter("#TransportCostValues cached ");
//		this.ttRequestedCounter = new Counter("numTravelCosts requested ");
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
		if (fromId.equals(toId)) {
			return 0.0;
		}
		if (vehicle == null) {
			vehicle = getDefaultVehicle(fromId);
		}
		if(departureTime < 0) {
			departureTime = 0;
		}
		String typeId = vehicle.getType().getTypeId();
		int timeSlice = getTimeSlice(departureTime);	
		
		Id<Link> fromLinkId = Id.create(fromId.getId(), Link.class);
		Id<Link> toLinkId = Id.create(toId.getId(), Link.class);
		Link fromLink = network.getLinks().get(fromLinkId);
		Link toLink = network.getLinks().get(toLinkId);
		
		Boolean isUsingZones = checkZoneUsage(fromLink, toLink);

		int fromZone = 0;
		int toZone = 0;
		
		if(isUsingZones) {	
			fromZone = (int) fromLink.getAttributes().getAttribute("zone");
			toZone = (int) toLink.getAttributes().getAttribute("zone");
		}
		
		TransportData data = null;
		TransportDataKey transportDataKey = null;
		if(isUsingZones && fromZone != toZone) {
			transportDataKey = makeKey(fromZone +"_zone", toZone +"_zone", timeSlice, typeId);
			data = costCache.get(transportDataKey);
		} else {			
			transportDataKey = makeKey(fromId.getId(), toId.getId(), timeSlice, typeId);
			data = costCache.get(transportDataKey);
		}

		double transportTime;

		if (data != null) {
			transportTime = data.transportTime;
		} else {
			informStartCalc();

			// because path not includes in&out Link
			org.matsim.vehicles.Vehicle matsimVehicle = getMatsimVehicle(vehicle);
			LeastCostPathCalculator router = createLeastCostPathCalculator();
			if(departureTime < 0) {
				System.out.println("Time: " +departureTime);
				System.out.println("Vehicle: " +matsimVehicle.getId());			
				System.out.println("Type: " +matsimVehicle.getType().toString());
				System.out.println("From: " +fromLink.toString());
				System.out.println("From: " +fromId);
				System.out.println("To: " +toLink.toString());
				System.out.println("To: " +toId);
			}
			Path path = router.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(), departureTime, null,
					matsimVehicle);
//			if(path == null) return Double.MAX_VALUE;
			double additionalCostTo = travelDisutility.getLinkTravelDisutility(toLink, departureTime + path.travelTime,
					null, matsimVehicle);
			double additionalTimeTo = travelTime.getLinkTravelTime(toLink, departureTime + path.travelTime, null,
					matsimVehicle);

			double travelDistance = fromLink.getLength();
			Iterator<Link> iter = path.links.iterator();
			while (iter.hasNext()) {
				Link link = iter.next();
				travelDistance = travelDistance + link.getLength();
			}
			transportTime = path.travelTime;
			TransportData newData = new TransportData(path.travelCost + additionalCostTo,
					path.travelTime + additionalTimeTo, travelDistance);
			TransportData existingData = costCache.putIfAbsent(transportDataKey, newData);
//			ttMemorizedCounter.incCounter();
			if (existingData == null) {
				existingData = newData;
			}
			transportTime = existingData.transportTime;
			informEndCalc();
		}
		return transportTime;
	}

	private VehicleImpl getDefaultVehicle(Location fromId) {
		return VehicleImpl.Builder.newInstance("default").setType(
				com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl.Builder.newInstance(defaultTypeId).build())
				.setStartLocation(fromId).build();
	}

	private void informEndCalc() {
		for (InternalLeastCostPathCalculatorListener l : listeners)
			l.endCalculation(Thread.currentThread().getId());
	}

	private void informStartCalc() {
		for (InternalLeastCostPathCalculatorListener l : listeners)
			l.startCalculation(Thread.currentThread().getId());
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
		if (fromId == null || toId == null)
			throw new IllegalStateException("either fromId (" + fromId + ") or toId (" + toId
					+ ") is null [departureTime=" + departureTime + "][vehicle=" + vehicle + "]");
		if (fromId.equals(toId)) {
			return 0.0;
		}
		if (vehicle == null) {
			vehicle = getDefaultVehicle(fromId);
		}

		LeastCostPathCalculator router = createLeastCostPathCalculator();
		int timeSlice = getTimeSlice(departureTime);
		String typeId = vehicle.getType().getTypeId();
		
		Id<Link> fromLinkId = Id.create(fromId.getId(), Link.class);
		Id<Link> toLinkId = Id.create(toId.getId(), Link.class);
		Link fromLink = network.getLinks().get(fromLinkId);
		Link toLink = network.getLinks().get(toLinkId);
		
		Boolean isUsingZones = checkZoneUsage(fromLink, toLink);
		int fromZone = 0;
		int toZone = 0;
		
		if(isUsingZones) {	
			fromZone = (int) fromLink.getAttributes().getAttribute("zone");
			toZone = (int) toLink.getAttributes().getAttribute("zone");
		}
		
		TransportData data = null;
		TransportDataKey transportDataKey = null;
		if(isUsingZones && fromZone != toZone) {
			transportDataKey = makeKey(fromZone +"_zone", toZone +"_zone", timeSlice, typeId);
			data = costCache.get(transportDataKey);
		} else {			
			transportDataKey = makeKey(fromId.getId(), toId.getId(), timeSlice, typeId);
			data = costCache.get(transportDataKey);
		}		

		double transportCost;

		if (data != null) {
			transportCost = data.transportCosts;
		} else {
			informStartCalc();
			org.matsim.vehicles.Vehicle matsimVehicle = getMatsimVehicle(vehicle);
			Path path = router.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(), departureTime, null,
					matsimVehicle);
//			if(path == null) return Double.MAX_VALUE;
			double additionalCostTo = travelDisutility.getLinkTravelDisutility(toLink, departureTime + path.travelTime,
					null, matsimVehicle);
			double additionalTimeTo = travelTime.getLinkTravelTime(toLink, departureTime + path.travelTime, null,
					matsimVehicle);
			double travelDistance = fromLink.getLength();
			Iterator<Link> iter = path.links.iterator();
			while (iter.hasNext()) {
				Link link = iter.next();
				travelDistance = travelDistance + link.getLength();
			}

			TransportData newData = new TransportData(path.travelCost + additionalCostTo,
					path.travelTime + additionalTimeTo, travelDistance);
			TransportData existingData = costCache.putIfAbsent(transportDataKey, newData);
//			ttMemorizedCounter.incCounter();
			if (existingData == null) {
				// succeeded
				existingData = newData;
			}
			transportCost = existingData.transportCosts;
			informEndCalc();
		}
		return transportCost;
	}

	private Boolean checkZoneUsage(Link fromLink, Link toLink) {		
		
		if(fromLink.getAttributes().getAttribute("zone") == null) {	
			return false;
		}
        return toLink.getAttributes().getAttribute("zone") != null;
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
		if (fromId.equals(toId)) {
			return 0.0;
		}
		if (vehicle == null) {
			vehicle = getDefaultVehicle(fromId);
		}
		String typeId = vehicle.getType().getTypeId();
		int timeSlice = getTimeSlice(departureTime);
		Id<Link> fromLinkId = Id.create(fromId.getId(), Link.class);
		Id<Link> toLinkId = Id.create(toId.getId(), Link.class);
		Link fromLink = network.getLinks().get(fromLinkId);
		Link toLink = network.getLinks().get(toLinkId);	
		
		Boolean isUsingZones = checkZoneUsage(fromLink, toLink);
		int fromZone = 0;
		int toZone = 0;
		
		if(isUsingZones) {	
			fromZone = (int) fromLink.getAttributes().getAttribute("zone");
			toZone = (int) toLink.getAttributes().getAttribute("zone");
		}
		
		TransportData data = null;
		TransportDataKey transportDataKey = null;
		if(isUsingZones && fromZone != toZone) {
			transportDataKey = makeKey(fromZone +"_zone", toZone +"_zone", timeSlice, typeId);
			data = costCache.get(transportDataKey);
		} else {			
			transportDataKey = makeKey(fromId.getId(), toId.getId(), timeSlice, typeId);
			data = costCache.get(transportDataKey);
		}
		double travelDistance;
		if (data != null) {
			travelDistance = data.transportDistance;
		} else {
			informStartCalc();

			travelDistance = fromLink.getLength();
			org.matsim.vehicles.Vehicle matsimVehicle = getMatsimVehicle(vehicle);
			LeastCostPathCalculator router = createLeastCostPathCalculator();
			Path path = router.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(), departureTime, null,
					matsimVehicle);
//			if(path == null) return Double.MAX_VALUE;
			double additionalCostTo = travelDisutility.getLinkTravelDisutility(toLink, departureTime + path.travelTime,
					null, matsimVehicle);
			double additionalTimeTo = travelTime.getLinkTravelTime(toLink, departureTime + path.travelTime, null,
					matsimVehicle);
			Iterator<Link> iter = path.links.iterator();
			while (iter.hasNext()) {
				Link link = iter.next();
				travelDistance = travelDistance + link.getLength();
			}
			TransportData newData = new TransportData(path.travelCost + additionalCostTo,
					path.travelTime + additionalTimeTo, travelDistance);
			TransportData existingData = costCache.putIfAbsent(transportDataKey, newData);
//			ttMemorizedCounter.incCounter();
			if (existingData == null) {
				existingData = newData;
			}
			travelDistance = existingData.transportDistance;
			informEndCalc();
		}
		return travelDistance;
	}

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
		String typeId = vehicle.getType().getTypeId();
		org.matsim.vehicles.Vehicle matsimVehicle = matsimVehicles.get(typeId);
		if (matsimVehicle != null) {
			return matsimVehicle;
		}
		matsimVehicle = new MatsimVehicleWrapper(vehicle);
		matsimVehicles.put(typeId, matsimVehicle);
		return matsimVehicle;
	}

	private TransportDataKey makeKey(String fromId, String toId, long time, String vehicleType) {
		return new TransportDataKey(fromId, toId, time, vehicleType);
	}

	public LeastCostPathCalculator getRouter() {
		return createLeastCostPathCalculator();
	}

	private LeastCostPathCalculator createLeastCostPathCalculator() {
		LeastCostPathCalculator router = routerCache.get(Thread.currentThread().getId());
		if (router == null) {
			LeastCostPathCalculator newRouter = leastCostPathCalculatorFactory.createPathCalculator(network,
					travelDisutility, travelTime);
			router = routerCache.putIfAbsent(Thread.currentThread().getId(), newRouter);
			if (router == null) {
				router = newRouter;
			}
		}
		return router;
	}

	private int getTimeSlice(double time) {
		int timeSlice = (int) (time / timeSliceWidth);
		return timeSlice;
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

}
