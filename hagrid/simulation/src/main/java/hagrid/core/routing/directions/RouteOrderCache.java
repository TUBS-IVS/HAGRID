package hagrid.core.routing.directions;

import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Caches immutable values derived solely from a route's ordered activity identities.
 *
 * <p>Native jsprit creates a fresh insertion context for each job/route evaluation and
 * keeps that route structurally unchanged while enumerating its positions. We check
 * the full snapshot once per such query, then reuse it for its candidate positions.
 * A caller must use a new context after a route mutation, just as native jsprit does.
 * This cache is deliberately unsuitable for values depending on time, load, vehicle,
 * mutable activity properties, or the candidate job. Direction-DP values use only order.
 *
 * <p>Workers have independent bounded route maps. State belongs to this cache, not a
 * long-lived worker ThreadLocal, so releasing a solver also releases its cached routes.
 * Call clear at iteration/search boundaries once workers have finished their queries.
 */
public final class RouteOrderCache<T> {
    private final Function<VehicleRoute,T> builder;
    private final int maxRoutesPerThread;
    private final ConcurrentHashMap<Thread, State<T>> workers = new ConcurrentHashMap<>();

    public RouteOrderCache(Function<VehicleRoute,T> builder, int maxRoutesPerThread) {
        this.builder = Objects.requireNonNull(builder);
        if (maxRoutesPerThread < 1) throw new IllegalArgumentException("At least one route must fit");
        this.maxRoutesPerThread = maxRoutesPerThread;
    }

    public T get(JobInsertionContext context) {
        var thread = Thread.currentThread();
        var state = workers.get(thread);
        if (state == null) {
            state = new State<>();
            workers.put(thread, state); // A thread is the sole writer of its own state.
        }
        var route = context.getRoute();
        var activities = route.getActivities();
        if (state.query == context && state.last.activities.length == activities.size()) return state.last.value;
        var entry = state.routes.get(route);
        if (entry == null || !entry.matches(activities)) {
            if (entry == null && state.routes.size() >= maxRoutesPerThread) state.routes.clear();
            entry = new Entry<>(activities.toArray(TourActivity[]::new), Objects.requireNonNull(builder.apply(route)));
            state.routes.put(route, entry);
        }
        state.query = context;
        state.last = entry;
        return entry.value;
    }

    public void clear() { workers.clear(); }

    private static final class State<T> {
        final IdentityHashMap<VehicleRoute, Entry<T>> routes = new IdentityHashMap<>();
        JobInsertionContext query;
        Entry<T> last;
    }

    private record Entry<T>(TourActivity[] activities, T value) {
        boolean matches(List<TourActivity> other) {
            if (activities.length != other.size()) return false;
            for (int i = 0; i < activities.length; i++) if (activities[i] != other.get(i)) return false;
            return true;
        }
    }
}
