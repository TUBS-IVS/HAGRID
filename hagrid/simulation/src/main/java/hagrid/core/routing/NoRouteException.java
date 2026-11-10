package hagrid.core.routing;
/** Only the absence of an admissible path; profile/cancellation/range errors use their own types. */
public final class NoRouteException extends IllegalArgumentException {
    public NoRouteException(String message) { super(message); }
}
