package hagrid.core.routing.sections;
import java.util.List;
public record SectionMember(String jobId, double chainageMeters, List<HaltApproach> approaches) {
 public SectionMember { approaches = List.copyOf(approaches); }
}
