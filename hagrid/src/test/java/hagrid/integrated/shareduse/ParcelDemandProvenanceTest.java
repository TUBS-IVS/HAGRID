package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preprocessing losses have to reach the KPI layer, and only a written file can carry them:
 * a stop dropped at its own yard gate never becomes an agent, so no plan, no event and no
 * MATSim output file mentions its parcels. Without this artefact the KPI layer can only report
 * 1c's delivery rate on the INJECTED base, which reads 100 % — identical to the Baseline's 100 %
 * on the full demand base, while 1c actually delivered 15 parcels fewer (spec 2026-08-25 §3).
 *
 * <p>The file lands next to the clipped population in {@code hagrid-output/<runId>/}, the sibling
 * directory the KPI layer already reads preprocessing artefacts from
 * ({@code build_kpis.py} for the fleet file, {@code extract_modular.py} for carriers).</p>
 */
class ParcelDemandProvenanceTest {

    private static Map<String, String> read(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(f)) {
            String[] p = line.split(";", 2);
            if (p.length == 2) {
                out.put(p[0], p[1]);
            }
        }
        return out;
    }

    @Test
    @DisplayName("every counter is written under a stable name, losses split by cause")
    void writesEveryCounterUnderAStableName(@TempDir Path dir) throws IOException {
        // 100 injected, 15 lost at their own yard gate, 4 clipped outside the area
        ParcelAgentGenerator.Result r = new ParcelAgentGenerator.Result(12, 100, 2, 1, 15, 4);
        Path f = dir.resolve("RUN_parcel_demand_provenance.csv");

        ParcelDemandProvenance.write(f, r);

        Map<String, String> m = read(f);
        assertEquals("100", m.get("parcels_injected_preprocessing"));
        assertEquals("15", m.get("parcels_dropped_at_depot_link"));
        assertEquals("4", m.get("parcels_clipped_outside_area"));
        assertEquals("119", m.get("parcels_offered"));
        assertEquals("2", m.get("stops_dropped_at_depot_link"));
        assertEquals("1", m.get("stops_clipped_outside_area"));
    }

    /**
     * The two loss channels must stay separable in the file, not just in the record. Collapsing
     * them into one "lost" number would make the model artefact (yard gate) unattributable
     * against the demand-definition loss (outside the area) — and only the first one is a
     * difference against the Baseline, which has no from==to constraint at all.
     */
    @Test
    @DisplayName("a yard-gate loss is not reported as a clipping loss")
    void keepsTheTwoLossChannelsApart(@TempDir Path dir) throws IOException {
        ParcelAgentGenerator.Result r = new ParcelAgentGenerator.Result(12, 100, 2, 0, 15, 0);
        Path f = dir.resolve("RUN_parcel_demand_provenance.csv");

        ParcelDemandProvenance.write(f, r);

        Map<String, String> m = read(f);
        assertEquals("15", m.get("parcels_dropped_at_depot_link"));
        assertEquals("0", m.get("parcels_clipped_outside_area"));
    }

    /**
     * The file must not be able to disagree with itself: whoever reads it can check the parts
     * against the total without holding the Result. This is the written form of the invariant
     * that made the 15 parcels visible in the first place.
     */
    @Test
    @DisplayName("the written total equals the written parts")
    void theWrittenTotalEqualsTheWrittenParts(@TempDir Path dir) throws IOException {
        ParcelAgentGenerator.Result r = new ParcelAgentGenerator.Result(700, 6037, 2, 0, 15, 0);
        Path f = dir.resolve("RUN_parcel_demand_provenance.csv");

        ParcelDemandProvenance.write(f, r);

        Map<String, String> m = read(f);
        int parts = Integer.parseInt(m.get("parcels_injected_preprocessing"))
                + Integer.parseInt(m.get("parcels_dropped_at_depot_link"))
                + Integer.parseInt(m.get("parcels_clipped_outside_area"));
        assertEquals(Integer.parseInt(m.get("parcels_offered")), parts);
        assertEquals(6052, parts, "the real dep7 figures: 6037 injected + 15 at the yard gate");
    }

    @Test
    @DisplayName("the header names the two columns so the file is readable without the writer")
    void writesAHeader(@TempDir Path dir) throws IOException {
        ParcelAgentGenerator.Result r = new ParcelAgentGenerator.Result(1, 1, 0, 0, 0, 0);
        Path f = dir.resolve("RUN_parcel_demand_provenance.csv");

        ParcelDemandProvenance.write(f, r);

        assertTrue(Files.readAllLines(f).get(0).startsWith("metric;value"),
                "same metric;value shape as shareduse_channel_stats.csv, so the KPI layer "
                        + "parses both with one reader");
    }
}
