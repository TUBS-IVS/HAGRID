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
    /** 07:30 earliest delivery/submission (rev. 2026-07-20), = the shared delivery-day start. */
    public static final double SUBMIT_FROM_S = hagrid.integrated.DeliveryDay.START_S;
    /** Latest request SUBMISSION (not a deadline): requests trickle in over the morning. */
    public static final double SUBMIT_TO_S = 10 * 3600.0;

    /**
     * Delivery deadline, 21:00 — the SAME for B2B and B2C and the same as the Baseline and 1d
     * (user decision 2026-07-30, {@link hagrid.integrated.DeliveryDay}). Replaces the earlier
     * per-type split (B2B 17:00 / B2C 20:00), which made 1c stricter than the arms it is compared
     * against. The per-parcel {@link #WINDOW_END_ATTRIBUTE} stays — the retry queue and KPI handler
     * read it per parcel — it is merely uniform now.
     */
    public static final double WINDOW_END_S = hagrid.integrated.DeliveryDay.END_S;

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
