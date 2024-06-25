package hagrid.utils.general;

import org.geotools.data.shapefile.ShapefileDataStoreFactory;

import org.geotools.data.DefaultTransaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DataUtilities;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.SchemaException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;

import java.util.Map;
import java.util.Optional;
import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.ConcurrentModificationException;
import java.util.HashMap;

/**
 * Utility class for HAGRID that includes methods for converting carrier
 * attributes to strings and other general functions.
 */
public class HAGRIDUtils {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDUtils.class);

    /**
     * Retrieves the specified scenario element.
     *
     * @param elementName The name of the scenario element to retrieve.
     * @param <T>         The type of the scenario element.
     * @return The scenario element.
     * @throws IllegalStateException If the scenario element is missing.
     */
    public static <T> T getScenarioElementAs(String elementName, Scenario scenario) {
        Object element = scenario.getScenarioElement(elementName);
        if (element == null) {
            throw new IllegalStateException(elementName + " data is missing in the scenario.");
        }
        return (T) element;
    }

    public static void checkAndLogCarrierAttributes(Carriers carriers) {
        LOGGER.info("Starting to check and log carrier attributes");

        // Explicitly specify the type arguments for HashMap
        Map<Id<Carrier>, Carrier> carrierMap = new HashMap<Id<Carrier>, Carrier>(carriers.getCarriers());

        for (Carrier carrier : carrierMap.values()) {
            Map<String, Object> carrierAttributes = new HashMap<String, Object>(carrier.getAttributes().getAsMap());
            checkAndLogAttributes(carrierAttributes, carrier.getId().toString());

            Map<Id<CarrierService>, CarrierService> servicesMap = new HashMap<Id<CarrierService>, CarrierService>(
                    carrier.getServices());

            for (CarrierService service : servicesMap.values()) {
                Map<String, Object> serviceAttributes = new HashMap<String, Object>(service.getAttributes().getAsMap());
                checkAndLogAttributes(serviceAttributes, carrier.getId().toString());
            }
        }
    }

    private static void checkAndLogAttributes(Map<String, Object> attributesMap, String carrierId) {
        for (Map.Entry<String, Object> entry : attributesMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            try {
                // Enhanced logging
                LOGGER.debug("Processing key: " + key + ", value: " + value + " for Carrier ID: " + carrierId);

                // Attempt to convert the attribute to a string
                String valueAsString = value == null ? "null" : value.toString();

                LOGGER.debug("Successfully processed key: " + key + ", valueAsString: " + valueAsString
                        + " for Carrier ID: " + carrierId);
            } catch (ConcurrentModificationException e) {
                LOGGER.error("ConcurrentModificationException for Carrier ID: " + carrierId + ", Key: " + key, e);
                throw e; // Re-throw the exception after logging
            } catch (Exception e) {
                LOGGER.error("Exception for Carrier ID: " + carrierId + ", Key: " + key + ", Value: " + value, e);
            }
        }
    }

    /**
     * Adds skills to the vehicle types based on their IDs.
     *
     * @param vehicleTypes The CarrierVehicleTypes object containing all vehicle
     *                     types.
     */
    public static void addSkills(CarrierVehicleTypes vehicleTypes) {
        vehicleTypes.getVehicleTypes().values().forEach(vehicleType -> {
            String typeId = vehicleType.getId().toString();
            if (typeId.startsWith("ct_car") || typeId.startsWith("ct_bus") || typeId.startsWith("ct_motorbike")
                    || typeId.startsWith("ct_other") || typeId.startsWith("ct_egrocery_bike")
                    || typeId.startsWith("ct_egrocery_van") || typeId.startsWith("ct_cep_bike")
                    || typeId.startsWith("ct_cep_size_m") || typeId.startsWith("ct_cep_size_l")) {
                CarriersUtils.addSkill(vehicleType, "conventional");
            } else if (typeId.startsWith("ct_truck_heavy") || typeId.startsWith("ct_truck_light")
                    || typeId.startsWith("ct_truck_super_light")) {
                CarriersUtils.addSkill(vehicleType, "supply");
            }
        });
    }

    /**
     * Converts the demand from parcels to a shapefile.
     *
     * @param carriers The carriers containing the services.
     * @param path     The path where the shapefile will be saved.
     * @throws SchemaException If there is an error creating the schema.
     */
    public static void convertDemandFromParcelsToShapeFile(Carriers carriers, String path) throws SchemaException {
        LOGGER.info("Writing Shape-Files!");

        // Define the schema for the shapefile
        SimpleFeatureType shpType = DataUtilities.createType("CarrierService",
                "the_geom:Point:srid=25832," +
                        "toLink:String," +
                        "capDemand:Integer," +
                        "provider:String," +
                        "type:String," +
                        "carrier:String," +
                        "plz:String");

        // Create a feature collection to store the features
        DefaultFeatureCollection featureCollection = new DefaultFeatureCollection();
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(shpType);
        GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory();

        // Iterate over all carriers and their services to build features
        for (Carrier carrier : carriers.getCarriers().values()) {
            String provider = Optional.ofNullable(carrier.getAttributes().getAttribute("provider"))
                    .map(Object::toString)
                    .orElse("UnknownProvider");

            String plz = Optional.ofNullable(carrier.getAttributes().getAttribute("plz"))
                    .map(Object::toString)
                    .orElse("UnknownPLZ");

            for (CarrierService service : carrier.getServices().values()) {
                Coord coord = Optional.ofNullable((Coord) service.getAttributes().getAttribute("coord"))
                        .orElse(new Coord(0, 0)); // Default Coord

                Point point = geometryFactory.createPoint(new Coordinate(coord.getX(), coord.getY()));

                String type = Optional.ofNullable(service.getAttributes().getAttribute("type"))
                        .map(Object::toString)
                        .orElse("UnknownType");

                featureBuilder.set("the_geom", point);
                featureBuilder.set("toLink", Optional.ofNullable(service.getLocationLinkId())
                        .map(Id::toString)
                        .orElse("UnknownLink"));
                featureBuilder.set("capDemand", service.getCapacityDemand());
                featureBuilder.set("provider", provider);
                featureBuilder.set("type", type);
                featureBuilder.set("carrier", Optional.ofNullable(carrier.getId())
                        .map(Id::toString)
                        .orElse("UnknownCarrier"));
                featureBuilder.set("plz", plz);

                SimpleFeature feature = featureBuilder.buildFeature(service.getId().toString());
                featureCollection.add(feature);
            }
        }

        // Write the features to the shapefile
        try {
            File outputFile = new File(path);

            // Ensure that the shapefile does not already exist
            if (outputFile.exists()) {
                outputFile.delete();
            }

            Map<String, Serializable> params = new HashMap<>();
            params.put("url", outputFile.toURI().toURL());
            ShapefileDataStoreFactory dataStoreFactory = new ShapefileDataStoreFactory();

            ShapefileDataStore newDataStore = (ShapefileDataStore) dataStoreFactory.createNewDataStore(params);

            // Create the schema
            newDataStore.createSchema(shpType);

            // Explicitly set the typeName when writing features
            String typeName = newDataStore.getTypeNames()[0];

            // Now get the FeatureSource using the type name
            SimpleFeatureStore featureStore = (SimpleFeatureStore) newDataStore.getFeatureSource(typeName);

            // Write the features
            DefaultTransaction transaction = new DefaultTransaction("create");
            featureStore.setTransaction(transaction);
            try {
                featureStore.addFeatures(featureCollection);
                transaction.commit();
            } catch (Exception e) {
                transaction.rollback();
                throw e;
            } finally {
                transaction.close();
            }

            LOGGER.info("Shapefile written successfully to {}", path);

        } catch (MalformedURLException e) {
            LOGGER.error("Error creating shapefile URL: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error writing to shapefile: {}", e.getMessage());
        }
    }

}
