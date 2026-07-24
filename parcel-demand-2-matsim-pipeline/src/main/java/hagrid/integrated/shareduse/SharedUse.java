package hagrid.integrated.shareduse;

/** Shared-Use (cargo hitching) constants — single source of truth for the
 *  parcel classifier prefix, subpopulation, capacities and dwell model. */
public final class SharedUse {
    public static final String PARCEL_PERSON_PREFIX = "parcel_";
    public static final String PARCEL_SUBPOPULATION = "parcel";
    public static final int BASE_SEATS = 10;            // standard DRT / Baseline base vehicle (rev. 2026-07-20; re-baseline pending)
    public static final int SEATS = 8;                  // Shared-Use = base vehicle with 2 seats (back bench) repurposed -> cargo
    public static final int PARCEL_SLOTS = 20;          // the repurposed back-bench volume (~2 seats -> 20 parcel units)
    public static final String LOAD_ATTRIBUTE = "dvrp:load:parcels";  // DefaultDvrpLoadFromTrip prefix + dimension
    public static final String DWELL_ATTRIBUTE = "parcelDwellSeconds";
    public static final String PICKUP_ATTRIBUTE = "parcelPickupSeconds";   // per-request depot load time (scales with parcels)
    public static final String WINDOW_END_ATTRIBUTE = "parcelWindowEndSeconds";  // per-type delivery deadline
    public static final String CHANNEL_ATTRIBUTE = "parcelChannel";
    public static final String ACT_DEPOT = "parcelDepot";
    public static final String ACT_DELIVERY = "parcelDelivery";
    public static final double DEPOT_LOAD_PER_PARCEL_S = 30.0;  // bulk depot loading (faster than the 2 min/parcel door delivery)
    public static final double MAX_PICKUP_DURATION_S = 600.0;   // cap depot load time
    public static final int DURATION_PER_PARCEL_MIN = 2;    // parity: LmdCarrierBuilder (door delivery)
    public static final int MAX_DURATION_PER_STOP_MIN = 15; // parity: LmdCarrierBuilder
    public static final double SUBMIT_FROM_S = 7.5 * 3600.0; // 07:30 earliest delivery/submission (rev. 2026-07-20)
    public static final double SUBMIT_TO_S = 10 * 3600.0;
    public static final double B2B_WINDOW_END_S = 17 * 3600.0;   // business-hours recipient presence
    public static final double B2C_WINDOW_END_S = 20 * 3600.0;   // home recipient, wider window

    private SharedUse() {
    }

    /** Door-delivery dwell (dropoff), parity with LMD. */
    public static double segmentDwellSeconds(int parcels) {
        return Math.min(DURATION_PER_PARCEL_MIN * 60.0 * parcels, MAX_DURATION_PER_STOP_MIN * 60.0);
    }

    /** Depot loading dwell (pickup), scales with parcel count (rev. 2026-07-20 - flat 120 s was unrealistic). */
    public static double depotPickupSeconds(int parcels) {
        return Math.min(DEPOT_LOAD_PER_PARCEL_S * parcels, MAX_PICKUP_DURATION_S);
    }

    public static boolean isParcelPerson(String personId) {
        return personId.startsWith(PARCEL_PERSON_PREFIX);
    }
}
