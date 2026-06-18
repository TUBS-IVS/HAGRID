package hagrid.utils.general;

/**
 * Geographic study area for a HAGRID run. Orthogonal to {@link hagrid.HagridConfig.Scenario}
 * (which is a delivery-concept selector and geography-agnostic).
 *
 * <p>The {@link #folder()} value is the input subfolder under {@code hagrid-input/}.
 * {@code HANNOVER} uses the empty string so its inputs stay directly under
 * {@code hagrid-input/} — preserving the legacy layout and all existing runs.</p>
 */
public enum StudyArea {

    /** Default. Region Hannover (and its sub-municipalities via {@link Region}). Legacy input layout. */
    HANNOVER(""),

    /** Lausitz / Hoyerswerda — native matsim-lausitz DRT service area. */
    LAUSITZ_HOYERSWERDA("lausitz");

    private final String folder;

    StudyArea(String folder) {
        this.folder = folder;
    }

    /** Input subfolder under {@code hagrid-input/}; empty for {@link #HANNOVER}. */
    public String folder() {
        return folder;
    }
}
