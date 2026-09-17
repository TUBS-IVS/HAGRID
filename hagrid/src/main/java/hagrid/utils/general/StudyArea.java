package hagrid.utils.general;

/**
 * Geographic study area for a HAGRID run. Orthogonal to {@link hagrid.HagridConfig.Scenario}
 * (which is a delivery-concept selector and geography-agnostic).
 *
 * <p>The {@link #folder()} value is the input subfolder under {@code input/}, i.e.
 * every study area's inputs live in their own folder below {@code input/}.</p>
 */
public enum StudyArea {

    /** Default. Region Hannover (and its sub-municipalities via {@link Region}). */
    HANNOVER("hannover"),

    /** Lausitz / Hoyerswerda — native matsim-lausitz DRT service area. */
    LAUSITZ_HOYERSWERDA("lausitz");

    private final String folder;

    StudyArea(String folder) {
        this.folder = folder;
    }

    /** Input subfolder under {@code input/}. */
    public String folder() {
        return folder;
    }
}
