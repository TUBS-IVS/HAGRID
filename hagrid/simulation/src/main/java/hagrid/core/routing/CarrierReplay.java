package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Optional standalone diagnosis through the production optimization service; never a simulation checkpoint. */
public final class CarrierReplay {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "hagrid-carrier-replay-v1";
    private CarrierReplay() {}
    record Manifest(String version, String carrierId, String inputFingerprint, String contextFingerprint,
                    String contextDigest, String preparationMode, long seed, int iterations, boolean previousPlan,
                    Double previousScore, Double previousJspritScore, String planFingerprint, List<String> vehicleIds, List<String> typeIds, List<Double> activityArrivals, CarrierReplayPlanState planState, Map<String,String> payloadDigests) {}

    public static synchronized void exportCase(Path directory, Carrier carrier, RoutingRequest request,
                                               ResolvedHagridConfig config, long seed) {
        if (request.carrier() != carrier) throw new IllegalArgumentException("Replay request belongs to another carrier");
        if (!request.context().config().equals(config)) throw new IllegalArgumentException("Replay configuration mismatch");
        request.context().requireOpen();
        try { CanonicalJobValidator.requireConsistentPlan(carrier,carrier.getSelectedPlan()); }
        catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Replay previous plan is inconsistent: "+invalid.getMessage(),invalid);
        }
        Path target = directory.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IllegalArgumentException("Replay case already exists: " + target);
        int iterations = config.optimization().replanningIterations().orElseGet(
                () -> config.optimization().offlineIterations().orElseThrow(
                        () -> new IllegalArgumentException("Replay requires an explicit replanning or offline budget")));
        String input = CarrierInputFingerprint.of(carrier);
        boolean previous = carrier.getSelectedPlan() != null;
        var planState=previous?CarrierReplayPlanState.capture(carrier.getSelectedPlan()):null;
        try (var lease = request.context().acquireQuery()) {
            Files.createDirectories(target.getParent());
            Files.createDirectory(target);
            var detached = CarrierSearchInput.copy(carrier);
            var tours = new ArrayList<ScheduledTour>();
            if (previous) for (var tour : carrier.getSelectedPlan().getScheduledTours()) {
                tours.add(ScheduledTour.newInstance(tour.getTour().duplicate(), tour.getVehicle(), tour.getDeparture()));
                detached.getCarrierCapabilities().getCarrierVehicles().putIfAbsent(tour.getVehicle().getId(), tour.getVehicle());
            }
            var plan = new CarrierPlan(tours); plan.setCarrier(detached);
            detached.addPlan(plan); detached.setSelectedPlan(plan);
            var types = new CarrierVehicleTypes();
            carrier.getCarrierCapabilities().getVehicleTypes().forEach(t -> types.getVehicleTypes().put(t.getId(), t));
            detached.getCarrierCapabilities().getCarrierVehicles().values().forEach(v -> types.getVehicleTypes().putIfAbsent(v.getType().getId(), v.getType()));
            new CarrierVehicleTypeWriter(types).write(target.resolve("types.xml").toString());
            PreciseCarrierPlanXml.write(detached, target.resolve("carrier.xml"));
            // Certify the exact native input that will be read by a separate process.
            Carrier imported = readCarrier(target, carrier.getId().toString());
            var vehicleIds = carrier.getCarrierCapabilities().getCarrierVehicles().keySet().stream().map(Object::toString).sorted().toList();
            var typeIds = carrier.getCarrierCapabilities().getVehicleTypes().stream().map(t -> t.getId().toString()).sorted().toList();
            restoreCanonicalFleet(imported, vehicleIds, typeIds);
            if (!input.equals(CarrierInputFingerprint.of(imported))) throw new IOException("Native replay serialization changed carrier input");
            var arrivals = previous ? activityArrivals(carrier.getSelectedPlan()) : List.<Double>of();
            if (previous) { planState.restore(imported); restoreActivityArrivals(imported.getSelectedPlan(), arrivals); }
            String oldPlan = previous ? CarrierPlanFingerprint.of(carrier.getSelectedPlan()) : "";
            if (previous && !oldPlan.equals(CarrierPlanFingerprint.of(imported.getSelectedPlan())))
                throw new IOException("Native replay serialization changed previous plan");
            Path shared = ReplayContextArchive.save(target.getParent(), request.context());
            var digests = new TreeMap<String,String>();
            for (String name : List.of("carrier.xml", "types.xml")) digests.put(name, OptimizationResultStore.digest(target.resolve(name)));
            var manifest = new Manifest(VERSION, carrier.getId().toString(), input, request.context().fingerprint(),
                    OptimizationResultStore.digest(shared), request.preparationMode(), seed, iterations, previous,
                    previous ? carrier.getSelectedPlan().getScore() : null,
                    previous ? CarriersUtils.getJspritScore(carrier.getSelectedPlan()) : null, oldPlan, vehicleIds, typeIds, arrivals, planState, digests);
            // Original input changes cannot be committed as a complete case.
            if (!input.equals(CarrierInputFingerprint.of(carrier))) throw new IOException("Carrier input changed during replay export");
            Path pending = target.resolve(".manifest.pending");
            JSON.writeValue(pending.toFile(), manifest);
            Files.move(pending, target.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new UncheckedIOException("Cannot export carrier replay: " + e.getMessage(), e); }
    }

    public static CarrierOptimizationService.Result replay(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        try {
            Manifest manifest = JSON.readValue(target.resolve("manifest.json").toFile(), Manifest.class);
            if (!VERSION.equals(manifest.version()) || manifest.iterations() < 1) throw new IOException("Invalid replay manifest");
            if (!manifest.contextFingerprint().matches("[a-f0-9]{64}")) throw new IOException("Invalid replay context reference");
            for (String name : List.of("carrier.xml", "types.xml")) {
                if (!OptimizationResultStore.digest(target.resolve(name)).equals(manifest.payloadDigests().get(name)))
                    throw new IOException("Replay input checksum mismatch: " + name);
            }
            Path shared = target.getParent().resolve(".contexts").resolve(manifest.contextFingerprint() + ".zip");
            if (!Files.isRegularFile(shared)) throw new IOException("Missing replay context " + shared);
            if (!OptimizationResultStore.digest(shared).equals(manifest.contextDigest())) throw new IOException("Replay context checksum mismatch");
            try (var context = ReplayContextArchive.read(shared)) {
                ReplayContextArchive.requireFingerprint(context, manifest.contextFingerprint());
                Carrier carrier = readCarrier(target, manifest.carrierId());
                restoreCanonicalFleet(carrier, manifest.vehicleIds(), manifest.typeIds());
                if (!manifest.inputFingerprint().equals(CarrierInputFingerprint.of(carrier)))
                    throw new IOException("Replay canonical input fingerprint mismatch");
                if (manifest.previousPlan()) {
                    if(manifest.planState()==null)throw new IOException("Missing precise previous plan metadata; recapture this replay" );
                    manifest.planState().restore(carrier);
                    restoreActivityArrivals(carrier.getSelectedPlan(), manifest.activityArrivals());
                    if (!manifest.planFingerprint().equals(CarrierPlanFingerprint.of(carrier.getSelectedPlan())))
                        throw new IOException("Replay previous plan fingerprint mismatch");
                    carrier.getSelectedPlan().setScore(manifest.previousScore());
                    if (manifest.previousJspritScore() != null) CarriersUtils.setJspritScore(carrier.getSelectedPlan(), manifest.previousJspritScore());
                } else { carrier.setSelectedPlan(null); carrier.getPlans().clear(); }
                var request = new RoutingRequest(carrier, context, manifest.preparationMode());
                return new CarrierOptimizationService().optimize(carrier, request, context.config(), manifest.seed(), manifest.iterations());
            }
        } catch (IOException e) { throw new UncheckedIOException("Cannot replay carrier case: " + e.getMessage(), e); }
    }

    /** Native reader adds a type collection; preserve the exact canonical domain, excluding old plan clones. */
    private static void restoreCanonicalFleet(Carrier carrier, List<String> vehicleIds, List<String> typeIds) {
        var vehicleKeys = vehicleIds.stream().map(Id::createVehicleId).collect(java.util.stream.Collectors.toSet());
        if (!carrier.getCarrierCapabilities().getCarrierVehicles().keySet().containsAll(vehicleKeys))
            throw new IllegalArgumentException("Replay canonical vehicle is missing");
        carrier.getCarrierCapabilities().getCarrierVehicles().keySet().retainAll(vehicleKeys);
        carrier.getCarrierCapabilities().getVehicleTypes().removeIf(t -> !typeIds.contains(t.getId().toString()));
        if (!new HashSet<>(typeIds).equals(carrier.getCarrierCapabilities().getVehicleTypes().stream()
                .map(t -> t.getId().toString()).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Replay canonical type is missing");
    }
    private static Carrier readCarrier(Path directory, String id) {
        var types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(directory.resolve("types.xml").toString());
        var carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(directory.resolve("carrier.xml").toString());
        Carrier carrier = carriers.getCarriers().get(Id.create(id, Carrier.class));
        if (carrier == null || carriers.getCarriers().size() != 1) throw new IllegalArgumentException("Wrong replay carrier");
        carrier.getPlans().forEach(p -> p.setCarrier(carrier));
        return carrier;
    }
    private static List<Double> activityArrivals(CarrierPlan plan) {
        return plan.getScheduledTours().stream().flatMap(t -> t.getTour().getTourElements().stream())
                .filter(Tour.TourActivity.class::isInstance).map(Tour.TourActivity.class::cast)
                .map(Tour.TourActivity::getExpectedArrival).toList();
    }
    private static void restoreActivityArrivals(CarrierPlan plan, List<Double> values) {
        if (plan == null) throw new IllegalArgumentException("Missing previous replay plan");
        var activities = plan.getScheduledTours().stream().flatMap(t -> t.getTour().getTourElements().stream())
                .filter(Tour.TourActivity.class::isInstance).map(Tour.TourActivity.class::cast).toList();
        if (activities.size() != values.size()) throw new IllegalArgumentException("Previous replay plan activity domain differs");
        for (int i = 0; i < activities.size(); i++) activities.get(i).setExpectedArrival(values.get(i));
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: hagrid.core.routing.CarrierReplay <case-directory>");
        var result = replay(Path.of(args[0]));
        System.out.println("carrier=" + result.carrierId() + " context=" + result.contextFingerprint()
                + " iterations=" + result.iterations() + " finalCost=" + result.finalCost()
                + " unassigned=" + result.unassignedJobIds() + " diagnostics=" + result.diagnostics());
        if (!result.diagnostics().isEmpty() || !result.unassignedJobIds().isEmpty())
            throw new IllegalStateException("Replay did not produce a complete validated result");
    }
}