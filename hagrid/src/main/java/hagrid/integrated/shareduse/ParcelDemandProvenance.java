package hagrid.integrated.shareduse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries the PREPROCESSING parcel losses to the KPI layer.
 *
 * <p>Why a file at all: a pooled stop dropped at its own yard gate never becomes an agent, so no
 * plan, no event and no MATSim output file mentions its parcels. They are unreconstructable from
 * the run output. Without them the KPI layer can only state 1c's delivery rate on the INJECTED
 * base, where it reads 100 % — indistinguishable from the Baseline's 100 % on the full demand
 * base, although 1c delivered 15 parcels fewer (spec 2026-08-25 §3). The Baseline has no such
 * loss: it routes parcels as jsprit {@code CarrierService}s, which have no {@code from == to}
 * constraint.
 *
 * <p>The file lands next to the clipped population in {@code hagrid-output/<runId>/}, the sibling
 * directory the KPI layer already reads preprocessing artefacts from, and uses the same
 * {@code metric;value} shape as {@code shareduse_channel_stats.csv} so one reader handles both.
 *
 * <p>{@code parcels_injected_preprocessing} is deliberately NOT named {@code parcels_injected}:
 * the handler writes that key from the simulation side, and the two being separate lets the KPI
 * layer cross-check them. They must be equal; a difference means parcels were lost between
 * preprocessing and the simulation, which nothing else would notice.
 */
public final class ParcelDemandProvenance {

    /** Appended to the run id, next to the clipped population. */
    public static final String SUFFIX = "_parcel_demand_provenance.csv";

    private ParcelDemandProvenance() {
    }

    /** The provenance file for a run, as a sibling of its clipped population file. */
    public static Path pathFor(Path clippedPopulation, String runId) {
        Path dir = clippedPopulation.toAbsolutePath().getParent();
        return dir.resolve(runId + SUFFIX);
    }

    public static void write(Path file, ParcelAgentGenerator.Result r) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("metric;value");
        lines.add("parcels_offered;" + r.parcelsOffered());
        lines.add("parcels_injected_preprocessing;" + r.parcels());
        lines.add("parcels_dropped_at_depot_link;" + r.skippedSameLinkParcels());
        lines.add("parcels_clipped_outside_area;" + r.clippedOutsideParcels());
        lines.add("stops_dropped_at_depot_link;" + r.skippedSameLink());
        lines.add("stops_clipped_outside_area;" + r.clippedOutside());
        lines.add("parcel_persons_injected;" + r.personsAdded());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
