package hagrid.lausitz.modular;

import java.util.Optional;

/** Start-window arithmetic shared by preparation and actual schedule updates. */
public final class ModularTourTiming {
    private ModularTourTiming() {}
    public record ServiceTiming(double waitSeconds, double startSeconds, double endSeconds) {}

    public static Optional<ServiceTiming> service(double arrival, double duration,
            double earliest, double latest) {
        validateWindow(earliest, latest);
        if (!Double.isFinite(arrival) || arrival < 0 || !Double.isFinite(duration) || duration < 0)
            throw new IllegalArgumentException("Invalid service arrival/duration");
        double start = Math.max(arrival, earliest);
        double end = start + duration;
        if (!Double.isFinite(end)) throw new IllegalArgumentException("Service clock overflow");
        return start > latest ? Optional.empty() : Optional.of(new ServiceTiming(start-arrival, start, end));
    }

    static void validateWindow(double earliest, double latest) {
        if (!Double.isFinite(earliest) || earliest < 0 || Double.isNaN(latest) || latest < earliest)
            throw new IllegalArgumentException("Invalid service start window: " + earliest + ".." + latest);
    }
}
