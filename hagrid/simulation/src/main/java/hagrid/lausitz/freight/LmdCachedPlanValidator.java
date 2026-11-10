package hagrid.lausitz.freight;

import hagrid.core.routing.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.misc.Time;
import org.matsim.freight.carriers.*;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Independent whole-file hit certificate against freshly built demand and the current regime. */
final class LmdCachedPlanValidator {
    private static final double EPS = 1e-6;
    private static final Pattern WAVE_COPY = Pattern.compile("(.+_h[0-9]+_v[0-9]+)_t[0-9]+");
    private LmdCachedPlanValidator() {}

    static void replay(Path file, Carriers canonical, CarrierVehicleTypes types, Network network,
                       int routeLimit, boolean baselineRetiming) throws IOException {
        replay(file,canonical,types,network,routeLimit,baselineRetiming,
                hagrid.core.config.RoutingDefaults.VEHICLE_AVAILABILITY_BUFFER_SECONDS,hagrid.core.config.RoutingDefaults.VEHICLE_DAY_END_SECONDS);
    }
    static void replay(Path file,Carriers canonical,CarrierVehicleTypes types,Network network,
            int routeLimit,boolean baselineRetiming,double availabilityBufferSeconds,double vehicleDayEndSeconds) throws IOException {
        try {
            var loaded = new Carriers();
            new CarrierPlanXmlReader(loaded, types).readFile(file.toString());
            if (!loaded.getCarriers().keySet().equals(canonical.getCarriers().keySet()))
                throw new IllegalArgumentException("Cached carrier domain differs from current demand");
            var providers = new HashMap<String, org.matsim.freight.carriers.jsprit.VRPTransportCosts>();
            for (var original : canonical.getCarriers().values()) {
                Carrier cached = loaded.getCarriers().get(original.getId());
                if (!metadataMatches(original, cached))
                    throw new IllegalArgumentException("Cached carrier metadata differs for " + original.getId());
                if (!CarrierInputFingerprint.jobs(original).equals(CarrierInputFingerprint.jobs(cached)))
                    throw new IllegalArgumentException("Cached jobs differ for carrier " + original.getId());
                if (cached.getCarrierCapabilities().getFleetSize() != original.getCarrierCapabilities().getFleetSize())
                    throw new IllegalArgumentException("Cached vehicle fleet regime differs for carrier " + original.getId());
                String mode = CarrierModeResolver.resolve(original).orElseThrow(
                        () -> new IllegalArgumentException("Missing carrier mode " + original.getId()));
                if (!Objects.equals(mode, CarrierModeResolver.resolve(cached).orElse(null)))
                    throw new IllegalArgumentException("Cached carrier mode differs " + original.getId());
                var validationInput = validationInput(original, cached, routeLimit, baselineRetiming,availabilityBufferSeconds,vehicleDayEndSeconds);
                var costs = providers.computeIfAbsent(mode, m -> NativeModalTransportCosts.create(network, types, m));
                var validation = PlanValidator.validate(validationInput, cached.getSelectedPlan(), costs, null, routeLimit);
                if (!validation.valid())
                    throw new IllegalArgumentException("Cached road plan invalid for carrier " + original.getId() + ": " + validation.violations());
            }
        } catch (RuntimeException e) {
            // The optional cache's existing IOException path falls back to a fresh search before any copy.
            throw new IOException("Unavailable validated Lausitz cache: " + e.getMessage(), e);
        }
    }

    /** Native XML omits the ID-valued missed list; its existing text/count are the persisted certificate. */
    private static boolean metadataMatches(Carrier original, Carrier cached) {
        var left = CarriersUtils.createCarrier(original.getId());
        var right = CarriersUtils.createCarrier(cached.getId());
        AttributesUtils.copyAttributesFromTo(original, left);
        AttributesUtils.copyAttributesFromTo(cached, right);
        Object missed = original.getAttributes().getAttribute("missedParcelsAsList");
        if (missed instanceof Collection<?> list) {
            Object text = original.getAttributes().getAttribute("missedParcelDeliveriesAsString");
            Object count = original.getAttributes().getAttribute("missedParcels");
            if (!Objects.equals(list.toString(), text) || !(count instanceof Integer n) || n != list.size())
                return false;
            Object cachedList = cached.getAttributes().getAttribute("missedParcelsAsList");
            if (cachedList != null && (!(cachedList instanceof Collection<?> items)
                    || !items.stream().map(Object::toString).toList().equals(list.stream().map(Object::toString).toList())))
                return false;
            left.getAttributes().removeAttribute("missedParcelsAsList");
            right.getAttributes().removeAttribute("missedParcelsAsList");
        }
        return CarrierInputFingerprint.metadata(left).equals(CarrierInputFingerprint.metadata(right));
    }
    private static Carrier validationInput(Carrier original, Carrier cached, int routeLimit, boolean baselineRetiming,double availabilityBufferSeconds,double vehicleDayEndSeconds) {
        var result = CarriersUtils.createCarrier(original.getId());
        AttributesUtils.copyAttributesFromTo(original, result);
        result.getServices().putAll(original.getServices()); result.getShipments().putAll(original.getShipments());
        var fleet = CarrierCapabilities.Builder.newInstance().setFleetSize(original.getCarrierCapabilities().getFleetSize());
        var originals = original.getCarrierCapabilities().getCarrierVehicles();
        if (!cached.getCarrierCapabilities().getCarrierVehicles().keySet().containsAll(originals.keySet()))
            throw new IllegalArgumentException("Cached canonical vehicle missing for carrier " + original.getId());
        for (var candidate : cached.getCarrierCapabilities().getCarrierVehicles().values()) {
            CarrierVehicle prototype = originals.get(candidate.getId());
            boolean wave = false;
            if (prototype == null && baselineRetiming && original.getCarrierCapabilities().getFleetSize() == CarrierCapabilities.FleetSize.INFINITE) {
                var match = WAVE_COPY.matcher(candidate.getId().toString());
                if (match.matches()) { prototype = originals.get(Id.createVehicleId(match.group(1))); wave = prototype != null; }
            }
            if (prototype == null) throw new IllegalArgumentException("Unapproved cached vehicle " + candidate.getId());
            if (!candidate.getLinkId().equals(prototype.getLinkId())
                    || !candidate.getType().getId().equals(prototype.getType().getId()))
                throw new IllegalArgumentException("Changed cached vehicle profile/depot " + candidate.getId());
            double start = wave ? candidate.getEarliestStartTime() : serialized(prototype.getEarliestStartTime());
            double end = wave ? Math.min(start + routeLimit + availabilityBufferSeconds, vehicleDayEndSeconds)
                    : serialized(prototype.getLatestEndTime());
            if (!Double.isFinite(start) || start < 0 || start > end
                    || Math.abs(candidate.getEarliestStartTime() - start) > EPS
                    || Math.abs(candidate.getLatestEndTime() - end) > EPS)
                throw new IllegalArgumentException("Changed cached vehicle availability " + candidate.getId());
            if (wave && cached.getSelectedPlan() != null) {
                for (var tour : cached.getSelectedPlan().getScheduledTours())
                    if (tour.getVehicle().getId().equals(candidate.getId()) && Math.abs(tour.getDeparture() - start) > EPS)
                        throw new IllegalArgumentException("Cached wave vehicle departure differs from availability " + candidate.getId());
            }
            fleet.addVehicle(CarrierVehicle.Builder.newInstance(candidate.getId(), prototype.getLinkId(), prototype.getType())
                    .setEarliestStart(start).setLatestEnd(end).build());
        }
        result.setCarrierCapabilities(fleet.build());
        return result;
    }
    private static double serialized(double seconds) {
        return Time.parseTime(Time.writeTime(seconds));
    }
}