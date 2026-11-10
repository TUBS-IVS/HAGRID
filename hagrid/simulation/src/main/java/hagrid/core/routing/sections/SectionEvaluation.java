package hagrid.core.routing.sections;
import java.util.List;
public record SectionEvaluation(boolean feasible, double variableCost, double endTime, List<SectionVisit> visits, long generatedLabels, long retainedLabels) {
 public SectionEvaluation { visits=List.copyOf(visits); }
}
