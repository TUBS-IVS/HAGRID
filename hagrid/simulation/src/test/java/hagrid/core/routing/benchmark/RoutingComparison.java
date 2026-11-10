package hagrid.core.routing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Benchmark-only comparison policy, kept outside the production JAR. */
public final class RoutingComparison {
    private RoutingComparison() { }

    public record Metrics(String fixture, String inputFingerprint, String rulesFingerprint,
                          String contextFingerprint, long seed, int iterationBudget, int heapMb,
                          int requiredJobs, int servedJobs, int hardViolations, int vehicles,
                          double finalCost, double milliseconds, long peakHeapBytes) { }

    public record Summary(boolean comparable, boolean improved, List<String> reasons) {
        public Summary { reasons = List.copyOf(reasons); }
    }

    public static Summary evaluate(Metrics baseline, Metrics candidate) {
        List<String> reasons = new ArrayList<>();
        if (!Objects.equals(baseline.fixture(), candidate.fixture())
                || !Objects.equals(baseline.inputFingerprint(), candidate.inputFingerprint())
                || !Objects.equals(baseline.contextFingerprint(), candidate.contextFingerprint())
                || baseline.requiredJobs() != candidate.requiredJobs()) reasons.add("different inputs or context");
        if (!Objects.equals(baseline.rulesFingerprint(), candidate.rulesFingerprint())) reasons.add("different evaluation rules");
        if (baseline.seed() != candidate.seed()) reasons.add("different seeds");
        if (baseline.iterationBudget() != candidate.iterationBudget()) reasons.add("different iteration budgets");
        if (baseline.heapMb() != candidate.heapMb()) reasons.add("different heap limits");
        if (!validNumbers(baseline) || !validNumbers(candidate)) reasons.add("invalid or unavailable measurements");
        if (!reasons.isEmpty()) return new Summary(false, false, reasons);

        if (baseline.servedJobs() != baseline.requiredJobs() || baseline.hardViolations() != 0)
            reasons.add("baseline is not a complete feasible reference");
        if (candidate.servedJobs() != candidate.requiredJobs()) reasons.add("candidate has unassigned jobs");
        if (candidate.hardViolations() != 0) reasons.add("candidate violates hard constraints");
        if (!reasons.isEmpty()) return new Summary(true, false, reasons);

        boolean memoryKnown = baseline.peakHeapBytes() >= 0 && candidate.peakHeapBytes() >= 0;
        if (!memoryKnown) reasons.add("memory measurement unavailable");
        boolean costNoWorse = candidate.finalCost() <= baseline.finalCost();
        boolean timeNoWorse = candidate.milliseconds() <= baseline.milliseconds();
        boolean memoryNoWorse = !memoryKnown || candidate.peakHeapBytes() <= baseline.peakHeapBytes();
        boolean gain = candidate.finalCost() < baseline.finalCost()
                || candidate.milliseconds() < baseline.milliseconds()
                || (memoryKnown && candidate.peakHeapBytes() < baseline.peakHeapBytes());
        boolean improved = costNoWorse && timeNoWorse && memoryNoWorse && gain;
        if (improved) reasons.add("observed numeric improvement; inspect measurement spread");
        else reasons.add("trade-off or no improvement");
        return new Summary(true, improved, reasons);
    }

    public static Summary evaluate(Metrics baseline, Metrics candidate,
                                   int baselineActualIterations, int candidateActualIterations) {
        if (baselineActualIterations <= 0 || candidateActualIterations <= 0
                || baselineActualIterations > baseline.iterationBudget()
                || candidateActualIterations > candidate.iterationBudget()) {
            return new Summary(false, false, List.of("invalid or variable actual iteration counts"));
        }
        if (baselineActualIterations != candidateActualIterations) {
            return new Summary(false, false, List.of("different actual iteration counts"));
        }
        return evaluate(baseline, candidate);
    }

    private static boolean validNumbers(Metrics metrics) {
        return Double.isFinite(metrics.finalCost()) && Double.isFinite(metrics.milliseconds())
                && metrics.milliseconds() >= 0 && metrics.requiredJobs() >= 0
                && metrics.servedJobs() >= 0 && metrics.servedJobs() <= metrics.requiredJobs()
                && metrics.hardViolations() >= 0 && metrics.vehicles() >= 0
                && metrics.iterationBudget() > 0 && metrics.heapMb() > 0;
    }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 4)
            throw new IllegalArgumentException("Supply baseline/candidate CSV metrics and optional actual iteration counts");
        Summary summary = args.length == 4
                ? evaluate(parse(args[0]), parse(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]))
                : evaluate(parse(args[0]), parse(args[1]));
        System.out.println("COMPARISON," + summary.comparable() + "," + summary.improved()
                + "," + String.join("; ", summary.reasons()));
    }

    private static Metrics parse(String csv) {
        String[] p = csv.split(",", -1);
        if (p.length != 14) throw new IllegalArgumentException("Expected 14 metric fields");
        return new Metrics(p[0], p[1], p[2], p[3], Long.parseLong(p[4]), Integer.parseInt(p[5]),
                Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]),
                Integer.parseInt(p[10]), Double.parseDouble(p[11]), Double.parseDouble(p[12]), Long.parseLong(p[13]));
    }
}
