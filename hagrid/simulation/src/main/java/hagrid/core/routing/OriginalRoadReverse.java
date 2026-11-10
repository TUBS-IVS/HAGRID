package hagrid.core.routing;

import org.matsim.api.core.v01.network.Link;

/** Legacy endpoint semantics, with explicit identity for split road children. */
public final class OriginalRoadReverse {
    private OriginalRoadReverse() {}
    private record Identity(String lineage, String from, String to) {}
    private static Identity identity(Link link) {
        Object from=link.getAttributes().getAttribute("sectionOriginalFromNodeId");
        Object to=link.getAttributes().getAttribute("sectionOriginalToNodeId");
        Object lineage=link.getAttributes().getAttribute("sectionOriginalLinkId");
        if ((from==null)!=(to==null) || (from!=null && (!(from instanceof String) || !(to instanceof String) || !(lineage instanceof String) || from.toString().isBlank() || to.toString().isBlank() || lineage.toString().isBlank())))
            throw new IllegalArgumentException("Incomplete split-road identity on " + link.getId());
        return from==null ? new Identity(link.getId().toString(),link.getFromNode().getId().toString(),link.getToNode().getId().toString())
                : new Identity(lineage.toString(),from.toString(),to.toString());
    }
    /** Activity locations need not be adjacent in the split graph. */
    public static boolean activityPair(Link from, Link to) {
        if(from==null || to==null || from.getId().equals(to.getId())) return false;
        var a=identity(from); var b=identity(to);
        if(a.lineage().equals(b.lineage())) {
            if(!a.from().equals(b.from()) || !a.to().equals(b.to()))
                throw new IllegalArgumentException("Inconsistent split-road lineage " + a.lineage());
            return false;
        }
        return a.from().equals(b.to()) && a.to().equals(b.from());
    }
    /** Actual road transitions additionally require physical directed connectivity. */
    public static boolean roadTransition(Link from, Link to) {
        return from!=null && to!=null && from.getToNode().getId().equals(to.getFromNode().getId()) && activityPair(from,to);
    }
}
