package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingMetricsCsvTest {
    @TempDir Path output;
    @Test void oldColumnsStayFirstAndUnknownCountersStayBlank() throws Exception {
        var metric=new CarrierRoutingMetrics("c","p",1,2,3,0,0,"SMALL","SERVICE","delivery",1,2,3);
        var path=output.resolve("unknown.csv");RoutingMetricsCsv.write(List.of(metric),path);
        var lines=Files.readAllLines(path);var header=lines.getFirst().split(",",-1);var row=lines.get(1).split(",",-1);
        assertEquals(25,header.length);assertEquals(header.length,row.length);
        assertArrayEquals(new String[]{"carrierId","provider","services","shipments","totalCapacityDemand","b2bParcels","b2cParcels",
                "sizeClass","threadingType","carrierType","jspritSeconds","routeSeconds","totalSeconds"},Arrays.copyOf(header,13));
        assertEquals("contextExactQueries",header[24]);
        assertEquals("",row[24]);
        assertFalse(List.of(header).contains("contextRepresentativeQueries"));
        assertFalse(List.of(header).contains("contextRepresentativeNoPathFallbacks"));
    }
    @Test void cumulativeMeasuredCountersAreWrittenUnderTheirOwnColumns() throws Exception {
        var metric=new CarrierRoutingMetrics("c","p",1,0,1,0,0,"SMALL","SERVICE","delivery",0,0,0)
                .withRoutingStatistics(new RoutingContext.Statistics(2,4,8,0,1,1,0,7,3,5));
        var path=output.resolve("measured.csv");RoutingMetricsCsv.write(List.of(metric),path);
        var lines=Files.readAllLines(path);var header=List.of(lines.getFirst().split(",",-1));var row=lines.get(1).split(",",-1);
        assertEquals("7",row[header.indexOf("contextExactQueries")]);
    }
}