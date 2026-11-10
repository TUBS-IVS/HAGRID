package hagrid.core.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One compatible CSV schema. The original thirteen columns stay first; unknown evidence stays blank. */
public final class RoutingMetricsCsv {
    private static final String HEADER = "carrierId,provider,services,shipments,totalCapacityDemand,b2bParcels,b2cParcels,"
            + "sizeClass,threadingType,carrierType,jspritSeconds,routeSeconds,totalSeconds,"
            + "contextFingerprint,validationStatus,actualIterations,preparationSeconds,searchSeconds,finalRoutingSeconds,"
            + "validationSeconds,searchCost,finalCost,matSimScore,searchTerminationReason,"
            + "contextExactQueries";
    private RoutingMetricsCsv() {}
    public static void write(Collection<CarrierRoutingMetrics> metrics, Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(HEADER); writer.newLine();
            for (var metric : metrics) {
                writer.write(row(metric)); writer.newLine();
            }
        }
    }
    private static String row(CarrierRoutingMetrics m) {
        var columns = new ArrayList<>(List.of(escape(m.carrierId),escape(m.provider),Integer.toString(m.services),
                Integer.toString(m.shipments),Integer.toString(m.totalCapacityDemand),Integer.toString(m.b2bParcels),
                Integer.toString(m.b2cParcels),escape(m.sizeClass),escape(m.threadingType),escape(m.carrierType),
                legacyTime(m.jspritSeconds),legacyTime(m.routeSeconds),legacyTime(m.totalSeconds)));
        if (m.execution.isEmpty()) columns.addAll(Collections.nCopies(11, ""));
        else {
            var e = m.execution.get();
            columns.add(escape(e.contextFingerprint())); columns.add(escape(e.validationStatus()));
            columns.add(Integer.toString(e.actualIterations()));
            if (e.phases().isEmpty()) columns.addAll(Collections.nCopies(4, ""));
            else {
                var p = e.phases().get();
                columns.add(number(p.preparationSeconds())); columns.add(number(p.searchSeconds()));
                columns.add(number(p.finalRoutingSeconds())); columns.add(number(p.validationSeconds()));
            }
            columns.add(number(e.searchCost())); columns.add(number(e.finalCost())); columns.add(number(e.matSimScore()));
            columns.add(escape(e.searchTerminationReason()));
        }
        if(m.routingStatistics.isEmpty())columns.addAll(Collections.nCopies(1,""));
        else {
            var stats=m.routingStatistics.get();
            columns.add(Long.toString(stats.exactQueries()));
        }
        return String.join(",", columns);
    }
    private static String legacyTime(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "";
    }
    private static String number(Double value) { return value != null && Double.isFinite(value) ? value.toString() : ""; }
    private static String escape(String value) {
        if (value == null) return "";
        boolean quote = value.chars().anyMatch(c -> c == ',' || c == '\n' || c == '\r' || c == '"');
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}