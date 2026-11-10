package hagrid.core.config;

/** Named compatibility defaults. Runtime consumers use the resolved run configuration. */
public final class RoutingDefaults {
    /** Profile defaults are owned here; study compatibility classes delegate to them. */
    public static final double LAUSITZ_DELIVERY_WINDOW_START_SECONDS = 27000;
    public static final double LAUSITZ_DELIVERY_WINDOW_END_SECONDS = 75600;
    private RoutingDefaults() {}
    /** Construct and validate initial tours; improve them during configured replanning. */
    public static final int INITIAL_SOLVER_ITERATIONS = 0;
    public static final int SOLVER_MAX_ROUTE_DURATION_SECONDS = 7 * 60 * 60;
    /** Vehicle availability extends the shared tour limit without extending the solver shift. */
    public static final int VEHICLE_AVAILABILITY_BUFFER_SECONDS = 60 * 60;
    public static final int VEHICLE_DAY_END_SECONDS = 21 * 60 * 60;
    public static final int REPLANNING_START_ITERATION = 25;
    public static final int CARRIERS_PER_REPLANNING_GROUP = 16;
}
