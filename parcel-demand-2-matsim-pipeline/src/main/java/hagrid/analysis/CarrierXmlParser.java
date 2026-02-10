package hagrid.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.stream.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Lightweight StAX-based carrier XML parser.
 * <p>
 * Extracts carrier metadata, services, and tour plans from
 * MATSim output carrier files (gzipped XML).
 */
public class CarrierXmlParser {

    private static final Logger LOG = LogManager.getLogger(CarrierXmlParser.class);
    private static final String NS = "http://www.matsim.org/files/dtd";

    public record ParsedService(
            String serviceId,
            String carrierIdRef,
            String toLinkId,
            int capacityDemand,
            int durationSec,
            int latestEndSec,
            Map<String, String> attributes
    ) {
        /**
         * Returns the set of original (pre-merge) service IDs that were merged into
         * this service, extracted from the "mergedMetadata" JSON attribute.
         * If no mergedMetadata exists, returns a singleton set of this service's own ID.
         */
        public Set<String> originalServiceIds() {
            String meta = attributes.getOrDefault("mergedMetadata", "");
            if (meta.isEmpty() || !meta.startsWith("{")) return Set.of(serviceId);
            // Lightweight extraction: find all keys "service_...":{ in the JSON
            Set<String> ids = new LinkedHashSet<>();
            int idx = 0;
            while ((idx = meta.indexOf('"', idx)) >= 0) {
                int end = meta.indexOf('"', idx + 1);
                if (end < 0) break;
                String key = meta.substring(idx + 1, end);
                if (key.startsWith("service_")) ids.add(key);
                idx = end + 1;
            }
            return ids.isEmpty() ? Set.of(serviceId) : ids;
        }
    }

    public record ParsedTour(
            String vehicleId,
            String carrierId,
            String tourId,
            List<TourLeg> legs,
            List<TourAct> acts
    ) {
        /** Build the MATSim event vehicle ID: freight_{carrierId}_veh_{vehicleId}_{tourId} */
        public String eventVehicleId() {
            return "freight_" + carrierId + "_veh_" + vehicleId + "_" + tourId;
        }
    }

    public record TourLeg(double departureTime, double expectedTranspTimeSec, List<String> routeLinkIds) {}
    public record TourAct(String type, String serviceId, String linkId, double estArrival, double estDeparture, double endTime) {}

    /**
     * Enhanced carrier record with all carrier-level attributes, provider info,
     * carrier type (delivery/supply), and cost accessors.
     */
    public record ParsedCarrier(
            String carrierId,
            String carrierType,         // "delivery" or "supply"
            String provider,            // e.g. "amazon", "dhl", "dpd", …
            int numServices,
            int numHandled,
            int numMissed,              // missed parcel count (from missedParcelDeliveriesAsString)
            int numberOfParcels,        // from carrier attribute "numberOfParcels"
            List<String> missedDeliveries,
            List<ParsedService> services,
            List<ParsedTour> tours,
            int totalDemand,
            Map<String, String> carrierAttributes,
            Map<String, String> vehicleTypeMap,   // vehicle ID → vehicle type ID
            Map<String, int[]> vehicleTimeWindows  // vehicle ID → [earliestStartSec, latestEndSec]
    ) {
        public boolean isSupply()   { return "supply".equals(carrierType); }
        public boolean isDelivery() { return "delivery".equals(carrierType); }

        // ── cost accessors ──
        public double costTotal()             { return dblAttr("costTotal"); }
        public double costDistance()           { return dblAttr("costDistance"); }
        public double costTime()              { return dblAttr("costTime"); }
        public double costFix()               { return dblAttr("costFix"); }
        public double costActivity()          { return dblAttr("costActivity"); }
        public double costOvertime()          { return dblAttr("costOvertime"); }
        public double costTimeWindowPenalty() { return dblAttr("costTimeWindowPenalty"); }

        // ── metric accessors ──
        public double totalDistanceMeters()     { return dblAttr("totalDistanceMeters"); }
        public double totalTravelTimeSeconds()  { return dblAttr("totalTravelTimeSeconds"); }
        public String hubId()                   { return carrierAttributes.getOrDefault("hubId", ""); }
        public String plz()                     { return carrierAttributes.getOrDefault("plz", ""); }

        /** Parcel-based success rate (%). */
        public double successRate() {
            int base = numberOfParcels > 0 ? numberOfParcels : numServices;
            return base > 0 ? 100.0 * (base - numMissed) / base : 100.0;
        }

        private double dblAttr(String key) {
            String v = carrierAttributes.getOrDefault(key, "0");
            try { return Double.parseDouble(v); } catch (Exception e) { return 0; }
        }
    }

    /**
     * Parse a gzipped MATSim carriers output XML.
     */
    public static List<ParsedCarrier> parse(Path file) throws Exception {
        LOG.info("Parsing carrier file: {}", file);

        InputStream is;
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            is = new GZIPInputStream(new FileInputStream(file.toFile()));
        } else {
            is = new FileInputStream(file.toFile());
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new BufferedInputStream(is));

        List<ParsedCarrier> carriers = new ArrayList<>();

        String currentCarrierId = null;
        List<ParsedService> currentServices = null;
        List<ParsedTour> currentTours = null;
        List<String> missedDeliveries = null;
        Map<String, String> currentCarrierAttrs = null;

        // Service parsing state
        String svcId = null, svcTo = null;
        int svcDemand = 0, svcDur = 0, svcLatestEnd = 0;
        Map<String, String> svcAttrs = null;
        boolean inService = false;
        boolean inServiceAttributes = false;
        String currentAttrName = null;

        // Plan/Tour state
        boolean inSelectedPlan = false;
        String tourVehicleId = null;
        String currentTourId = null;
        List<TourAct> tourActs = null;
        List<TourLeg> tourLegs = null;

        // Carrier attribute state
        boolean inCarrierAttributes = false;
        String carrierAttrName = null;

        // Vehicle fleet state (vehicle ID → typeId mapping)
        Map<String, String> currentVehicleTypeMap = null;
        Map<String, int[]> currentVehicleTW = null;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();

                switch (local) {
                    case "carrier" -> {
                        currentCarrierId = reader.getAttributeValue(null, "id");
                        currentServices = new ArrayList<>();
                        currentTours = new ArrayList<>();
                        missedDeliveries = new ArrayList<>();
                        currentCarrierAttrs = new LinkedHashMap<>();
                        currentVehicleTypeMap = new LinkedHashMap<>();
                        currentVehicleTW = new LinkedHashMap<>();
                        inSelectedPlan = false;
                    }
                    case "attributes" -> {
                        if (inService) {
                            inServiceAttributes = true;
                        } else if (currentCarrierId != null && !inSelectedPlan) {
                            inCarrierAttributes = true;
                        }
                    }
                    case "attribute" -> {
                        if (inServiceAttributes) {
                            currentAttrName = reader.getAttributeValue(null, "name");
                        } else if (inCarrierAttributes) {
                            carrierAttrName = reader.getAttributeValue(null, "name");
                        }
                    }
                    case "service" -> {
                        inService = true;
                        svcId = reader.getAttributeValue(null, "id");
                        svcTo = reader.getAttributeValue(null, "to");
                        svcDemand = safeInt(reader.getAttributeValue(null, "capacityDemand"));
                        svcDur = parseDuration(reader.getAttributeValue(null, "serviceDuration"));
                        svcLatestEnd = parseDuration(reader.getAttributeValue(null, "latestEnd"));
                        svcAttrs = new LinkedHashMap<>();
                    }
                    case "vehicle" -> {
                        // Capture vehicle fleet definitions (under <capabilities><vehicles>)
                        if (!inSelectedPlan && currentVehicleTypeMap != null) {
                            String vId = reader.getAttributeValue(null, "id");
                            String tId = reader.getAttributeValue(null, "typeId");
                            if (vId != null && tId != null) {
                                currentVehicleTypeMap.put(vId, tId);
                            }
                            // Capture vehicle time windows
                            if (vId != null && currentVehicleTW != null) {
                                int es = parseDuration(reader.getAttributeValue(null, "earliestStart"));
                                int le = parseDuration(reader.getAttributeValue(null, "latestEnd"));
                                currentVehicleTW.put(vId, new int[]{es, le});
                            }
                        }
                    }
                    case "plan" -> {
                        String selected = reader.getAttributeValue(null, "selected");
                        inSelectedPlan = "true".equals(selected);
                    }
                    case "tour" -> {
                        if (inSelectedPlan) {
                            tourVehicleId = reader.getAttributeValue(null, "vehicleId");
                            currentTourId = reader.getAttributeValue(null, "tourId");
                            tourActs = new ArrayList<>();
                            tourLegs = new ArrayList<>();
                        }
                    }
                    case "act" -> {
                        if (inSelectedPlan && tourActs != null) {
                            tourActs.add(new TourAct(
                                    reader.getAttributeValue(null, "type"),
                                    reader.getAttributeValue(null, "serviceId"),
                                    attr(reader, "link"),
                                    safeDouble(reader.getAttributeValue(null, "estArrival")),
                                    safeDouble(reader.getAttributeValue(null, "estDep")),
                                    parseDuration(reader.getAttributeValue(null, "end_time"))
                            ));
                        }
                    }
                    case "leg" -> {
                        if (inSelectedPlan && tourLegs != null) {
                            double depT = safeDouble(reader.getAttributeValue(null, "dep_time"));
                            if (depT == 0) depT = parseDuration(reader.getAttributeValue(null, "expected_dep_time"));
                            double transpT = parseDuration(reader.getAttributeValue(null, "expected_transp_time"));
                            tourLegs.add(new TourLeg(depT, transpT, new ArrayList<>()));
                        }
                    }
                    case "route" -> {
                        if (inSelectedPlan && tourLegs != null && !tourLegs.isEmpty()) {
                            String text = reader.getElementText();
                            if (text != null && !text.isBlank()) {
                                tourLegs.getLast().routeLinkIds().addAll(
                                        Arrays.asList(text.trim().split("\\s+"))
                                );
                            }
                        }
                    }
                }

            } else if (event == XMLStreamConstants.CHARACTERS) {
                String text = reader.getText().trim();
                if (!text.isEmpty()) {
                    if (inServiceAttributes && currentAttrName != null) {
                        // Use merge to handle text fragmentation across CHARACTERS events
                        svcAttrs.merge(currentAttrName, text, (old, nw) -> old + nw);
                    }
                    if (inCarrierAttributes && carrierAttrName != null && currentCarrierAttrs != null) {
                        // Use merge to handle text fragmentation across CHARACTERS events
                        currentCarrierAttrs.merge(carrierAttrName, text, (old, nw) -> old + nw);
                    }
                }

            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName();

                switch (local) {
                    case "service" -> {
                        if (currentServices != null && inService) {
                            currentServices.add(new ParsedService(
                                    svcId, currentCarrierId, svcTo, svcDemand, svcDur, svcLatestEnd, svcAttrs
                            ));
                        }
                        inService = false;
                        inServiceAttributes = false;
                        currentAttrName = null;
                    }
                    case "attributes" -> {
                        inServiceAttributes = false;
                        inCarrierAttributes = false;
                        currentAttrName = null;
                        carrierAttrName = null;
                    }
                    case "attribute" -> {
                        currentAttrName = null;
                        carrierAttrName = null;
                    }
                    case "tour" -> {
                        if (inSelectedPlan && tourVehicleId != null && currentTours != null) {
                            currentTours.add(new ParsedTour(
                                    tourVehicleId, currentCarrierId,
                                    currentTourId != null ? currentTourId : String.valueOf(currentTours.size() + 1),
                                    tourLegs != null ? tourLegs : List.of(),
                                    tourActs != null ? tourActs : List.of()
                            ));
                        }
                        tourVehicleId = null;
                        currentTourId = null;
                        tourActs = null;
                        tourLegs = null;
                    }
                    case "plan" -> inSelectedPlan = false;
                    case "carrier" -> {
                        if (currentCarrierId != null && currentServices != null) {
                            // Determine carrier type: delivery or supply
                            String cType = currentCarrierId.contains("supply") ? "supply" : "delivery";
                            String carrierTypeAttr = currentCarrierAttrs != null
                                    ? currentCarrierAttrs.getOrDefault("carrierType", cType) : cType;
                            if ("supply".equalsIgnoreCase(carrierTypeAttr)) cType = "supply";

                            // Extract provider from attributes or carrier ID
                            String provider = currentCarrierAttrs != null
                                    ? currentCarrierAttrs.getOrDefault("provider", "") : "";
                            if (provider.isEmpty()) {
                                provider = guessProvider(currentCarrierId);
                            }

                            // numberOfParcels from attribute
                            int numParcels = currentCarrierAttrs != null
                                    ? safeInt(currentCarrierAttrs.getOrDefault("numberOfParcels", "0")) : 0;

                            // Parse missedDeliveries from the FINAL merged text
                            // (deferred from CHARACTERS events to avoid text fragmentation)
                            String missedStr = currentCarrierAttrs != null
                                    ? currentCarrierAttrs.getOrDefault("missedParcelDeliveriesAsString", "") : "";
                            missedDeliveries = new ArrayList<>();
                            if (!missedStr.isEmpty()) {
                                String cleaned = missedStr.replace("[", "").replace("]", "").replace(" ", "");
                                for (String s : cleaned.split(",")) {
                                    if (!s.isEmpty()) missedDeliveries.add(s);
                                }
                            }

                            int totalDemand = currentServices.stream().mapToInt(ParsedService::capacityDemand).sum();
                            int missed = missedDeliveries.size();
                            int numHandled = (numParcels > 0 ? numParcels : currentServices.size()) - missed;

                            carriers.add(new ParsedCarrier(
                                    currentCarrierId,
                                    cType,
                                    provider,
                                    currentServices.size(),
                                    Math.max(0, numHandled),
                                    missed,
                                    numParcels,
                                    missedDeliveries,
                                    currentServices,
                                    currentTours != null ? currentTours : List.of(),
                                    totalDemand,
                                    currentCarrierAttrs != null ? currentCarrierAttrs : Map.of(),
                                    currentVehicleTypeMap != null ? currentVehicleTypeMap : Map.of(),
                                    currentVehicleTW != null ? currentVehicleTW : Map.of()
                            ));
                        }
                        currentCarrierId = null;
                        currentCarrierAttrs = null;
                    }
                }
            }
        }

        reader.close();
        is.close();

        long deliveryCount = carriers.stream().filter(c -> !c.isSupply()).count();
        long supplyCount   = carriers.stream().filter(ParsedCarrier::isSupply).count();
        LOG.info("Parsed {} carriers ({} delivery, {} supply)", carriers.size(), deliveryCount, supplyCount);
        return carriers;
    }

    /**
     * Parse a gzipped MATSim vehicle types XML and return a map of typeId → capacity ("other").
     */
    public static Map<String, Double> parseVehicleTypes(Path file) throws Exception {
        LOG.info("Parsing vehicle types file: {}", file);

        InputStream is;
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            is = new GZIPInputStream(new FileInputStream(file.toFile()));
        } else {
            is = new FileInputStream(file.toFile());
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new BufferedInputStream(is));

        Map<String, Double> capacities = new LinkedHashMap<>();
        String currentTypeId = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if ("vehicleType".equals(local)) {
                    currentTypeId = reader.getAttributeValue(null, "id");
                } else if ("capacity".equals(local) && currentTypeId != null) {
                    String other = reader.getAttributeValue(null, "other");
                    if (other != null) {
                        try {
                            capacities.put(currentTypeId, Double.parseDouble(other));
                        } catch (NumberFormatException e) {
                            LOG.warn("Invalid capacity for {}: {}", currentTypeId, other);
                        }
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("vehicleType".equals(reader.getLocalName())) {
                    currentTypeId = null;
                }
            }
        }

        reader.close();
        is.close();

        LOG.info("Parsed {} vehicle types: {}", capacities.size(), capacities);
        return capacities;
    }

    /**
     * Parse a gzipped MATSim vehicle types XML and return a map of typeId → fixedCostsPerDay.
     */
    public static Map<String, Double> parseVehicleTypeFixedCosts(Path file) throws Exception {
        LOG.info("Parsing vehicle type fixed costs from: {}", file);

        InputStream is;
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            is = new GZIPInputStream(new FileInputStream(file.toFile()));
        } else {
            is = new FileInputStream(file.toFile());
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new BufferedInputStream(is));

        Map<String, Double> fixedCosts = new LinkedHashMap<>();
        String currentTypeId = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if ("vehicleType".equals(local)) {
                    currentTypeId = reader.getAttributeValue(null, "id");
                } else if ("costInformation".equals(local) && currentTypeId != null) {
                    String fixed = reader.getAttributeValue(null, "fixedCostsPerDay");
                    if (fixed != null) {
                        try {
                            fixedCosts.put(currentTypeId, Double.parseDouble(fixed));
                        } catch (NumberFormatException e) {
                            LOG.warn("Invalid fixedCostsPerDay for {}: {}", currentTypeId, fixed);
                        }
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("vehicleType".equals(reader.getLocalName())) {
                    currentTypeId = null;
                }
            }
        }

        reader.close();
        is.close();

        LOG.info("Parsed {} vehicle type fixed costs: {}", fixedCosts.size(), fixedCosts);
        return fixedCosts;
    }

    /**
     * Parse per-km cost (€/km) for each vehicle type.
     * Reads the {@code costsPerMeter} attribute from {@code costInformation} and converts to €/km.
     */
    public static Map<String, Double> parseVehicleTypeCostsPerKm(Path file) throws Exception {
        LOG.info("Parsing vehicle type costs per km from: {}", file);

        InputStream is;
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            is = new GZIPInputStream(new FileInputStream(file.toFile()));
        } else {
            is = new FileInputStream(file.toFile());
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new BufferedInputStream(is));

        Map<String, Double> costsPerKm = new LinkedHashMap<>();
        String currentTypeId = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if ("vehicleType".equals(local)) {
                    currentTypeId = reader.getAttributeValue(null, "id");
                } else if ("costInformation".equals(local) && currentTypeId != null) {
                    String cpm = reader.getAttributeValue(null, "costsPerMeter");
                    if (cpm != null) {
                        try {
                            costsPerKm.put(currentTypeId, Double.parseDouble(cpm) * 1000.0);
                        } catch (NumberFormatException e) {
                            LOG.warn("Invalid costsPerMeter for {}: {}", currentTypeId, cpm);
                        }
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("vehicleType".equals(reader.getLocalName())) {
                    currentTypeId = null;
                }
            }
        }

        reader.close();
        is.close();

        LOG.info("Parsed {} vehicle type costs per km: {}", costsPerKm.size(), costsPerKm);
        return costsPerKm;
    }

    /** Best-effort provider guess from carrier ID string. */
    private static String guessProvider(String carrierId) {
        String lower = carrierId.toLowerCase();
        if (lower.contains("amazon")) return "amazon";
        if (lower.contains("dp/dhl") || lower.contains("dp_dhl")) return "dp/dhl";
        if (lower.contains("dhl"))    return "dhl";
        if (lower.contains("dpd"))    return "dpd";
        if (lower.contains("fedex"))  return "fedex";
        if (lower.contains("gls"))    return "gls";
        if (lower.contains("hermes")) return "hermes";
        if (lower.contains("ups"))    return "ups";
        return "other";
    }

    private static String attr(XMLStreamReader r, String name) {
        String v = r.getAttributeValue(null, name);
        return v != null ? v : "";
    }

    private static int safeInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static double safeDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            if (raw.contains(":")) {
                String[] parts = raw.split(":");
                return Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
            return (int) Double.parseDouble(raw);
        } catch (Exception e) { return 0; }
    }
}
