package hagrid.integrated.modular;

/** Constants for the 1d Modular (U-Shift capsule swap) scenario. Extended in Task 3. */
public final class Modular {
    /** Cargo capsule parcel capacity (spec §6.1). DOCUMENTED NEVER-BINDING (design D8):
     *  216 x 2 min dwell = 7.2h exceeds any tour cap <= 7h, so time always binds first.
     *  It sizes the jsprit vehicle; it is NOT a DvrpLoad dimension (design D7 / plan C5). */
    public static final int CARGO_CAPACITY_PARCELS = 216;
    public static final String CARGO_CAPSULE_TYPE_ID = "ushift_cargo_capsule";

    /** Delivery day (plan C4 revised, user 2026-07-28): parcels arrive at the depot overnight,
     *  same-day delivery 07:30-21:00 is what counts - NO dispatch waves in 1d. Used as the
     *  jsprit vehicle operating window AND the service-start time window. */
    public static final double DELIVERY_DAY_START_S = 7.5 * 3600.0;   // 07:30
    public static final double DELIVERY_DAY_END_S = 21 * 3600.0;      // 21:00

    private Modular() {}
}
