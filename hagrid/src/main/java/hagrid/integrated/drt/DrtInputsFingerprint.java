package hagrid.integrated.drt;

import hagrid.HagridConfig;
import hagrid.integrated.shareduse.SharedUse;
import hagrid.simulation.HAGRIDSimulationConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Fingerprint of the run-scoped DRT inputs produced by {@link LausitzDrtPreprocessor},
 * written next to them and re-checked before the simulation starts.
 *
 * <p><b>Why.</b> The prepared inputs (drt network, clipped plans, DVRP fleet) are produced by a
 * SEPARATE CLI ({@link PrepareLausitzDrtInputs}) and keyed only by
 * {@code CONCEPT_ddMMyyyy[_tag]} — {@code fleetSize}, the vehicle seat count and
 * {@code noParcels} are NOT part of the path. {@code validateInputFiles()} used to check only
 * that the files EXIST, so a run silently reused inputs prepared for a different fleet size,
 * a different seat count, or with parcels switched off. That is not hypothetical: every fleet
 * file on disk carries {@code capacity="8"}, written before {@link SharedUse#BASE_SEATS} was
 * raised to 10 on 2026-07-20, so a DRT_BASELINE re-run would quietly simulate 8-seaters while
 * the log says otherwise.
 *
 * <p>This class closes that gap by recording, at prepare time, the parameters and the raw-input
 * file identities the artifacts were derived from, and by reporting every drift at validate time
 * so the run aborts with a concrete instruction instead of producing subtly wrong results.
 *
 * <p><b>Format.</b> {@link Properties} rather than JSON: this is a build-artifact fingerprint,
 * not a published schema, and Properties gives exact string round-tripping with zero
 * dependencies (the project has no declared JSON library; {@code RunMetadataWriter} hand-rolls
 * its writer and never needs to read back).
 */
public final class DrtInputsFingerprint {

    /** File name suffix, resolved under the run directory alongside the prepared inputs. */
    public static final String FILE_SUFFIX = "drt_inputs.properties";

    private static final String K_RUN_ID = "runId";
    private static final String K_CONCEPT = "concept";
    private static final String K_FLEET_SIZE = "fleetSize";
    private static final String K_CAPACITY = "vehicleCapacity";
    private static final String K_NO_PARCELS = "noParcels";
    private static final String K_BASE_SEATS = "sharedUse.BASE_SEATS";
    private static final String K_SEATS = "sharedUse.SEATS";
    private static final String K_PARCEL_SLOTS = "sharedUse.PARCEL_SLOTS";
    private static final String SOURCE_PREFIX = "source.";

    private DrtInputsFingerprint() {}

    /**
     * The seat count the DVRP fleet is written with. Single source of truth shared by the
     * preprocessor and the validator — deriving it twice would let the guard itself drift
     * away from what it guards.
     */
    public static int expectedCapacity(HAGRIDSimulationConfig cfg) {
        HagridConfig.Scenario scenario = HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
        return scenario == HagridConfig.Scenario.DRT_SHAREDUSE
                ? SharedUse.SEATS : SharedUse.BASE_SEATS;
    }

    /** Writes the fingerprint for {@code cfg} to {@code out} (parent directories must exist). */
    public static void write(HAGRIDSimulationConfig cfg, Path out) {
        Properties p = new Properties();
        expected(cfg).forEach(p::setProperty);
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            p.store(w, "HAGRID DRT prepared-input fingerprint - regenerate via PrepareLausitzDrtInputs");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write DRT inputs fingerprint: " + out, e);
        }
    }

    /**
     * Compares the fingerprint stored at {@code file} against what {@code cfg} would produce now.
     *
     * @return human-readable mismatch descriptions; empty when the prepared inputs match the run
     *         configuration. A missing file yields a single entry telling the user to re-prepare.
     */
    public static List<String> mismatches(HAGRIDSimulationConfig cfg, Path file) {
        if (!Files.exists(file)) {
            return List.of("DRT inputs fingerprint missing: " + file.toAbsolutePath()
                    + " — the prepared inputs predate this check. Re-run PrepareLausitzDrtInputs"
                    + " for '" + cfg.getRunId() + "'.");
        }
        Properties stored = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read DRT inputs fingerprint: " + file, e);
        }

        Map<String, String> now = expected(cfg);
        List<String> out = new ArrayList<>();
        // Union of both key sets: a key that disappeared (raw input no longer consumed) or
        // appeared (new raw input) is drift too, not something to silently skip.
        for (String key : new TreeSet<>(union(now.keySet(), stored.stringPropertyNames()))) {
            String want = now.get(key);
            String have = stored.getProperty(key);
            if (want == null || !want.equals(have)) {
                out.add("  " + key + ": prepared=" + have + " but run wants=" + want);
            }
        }
        if (!out.isEmpty()) {
            out.add(0, "DRT inputs were prepared with a different configuration ("
                    + file.toAbsolutePath() + "). Re-run PrepareLausitzDrtInputs for '"
                    + cfg.getRunId() + "'. Drift:");
        }
        return out;
    }

    /** The fingerprint {@code cfg} implies right now — parameters plus raw-input identities. */
    private static Map<String, String> expected(HAGRIDSimulationConfig cfg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(K_RUN_ID, cfg.getRunId());
        m.put(K_CONCEPT, cfg.getConcept().toUpperCase());
        m.put(K_FLEET_SIZE, Integer.toString(cfg.getFleetSize()));
        m.put(K_CAPACITY, Integer.toString(expectedCapacity(cfg)));
        m.put(K_NO_PARCELS, Boolean.toString(cfg.isNoParcels()));
        // Seat/slot constants are baked into the artifacts, so a code-side revision (e.g.
        // BASE_SEATS 8 -> 10) must invalidate previously prepared inputs.
        m.put(K_BASE_SEATS, Integer.toString(SharedUse.BASE_SEATS));
        m.put(K_SEATS, Integer.toString(SharedUse.SEATS));
        m.put(K_PARCEL_SLOTS, Integer.toString(SharedUse.PARCEL_SLOTS));

        putSource(m, "networkRaw", cfg.getLausitzNetworkRaw());
        putSource(m, "plansRaw", cfg.getPassengerPlansRaw());
        putSource(m, "serviceAreaShp", cfg.getDrtServiceAreaShapefile());
        putSource(m, "depotCsv", cfg.getLmdDepotCsv());
        putSource(m, "transitScheduleRaw", cfg.getLausitzTransitScheduleRaw());
        putSource(m, "transitVehiclesRaw", cfg.getLausitzTransitVehiclesRaw());
        // Parcel demand only feeds the artifacts on a parcel-injecting Shared-Use run.
        if (HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase())
                == HagridConfig.Scenario.DRT_SHAREDUSE && !cfg.isNoParcels()) {
            putSource(m, "lmdDemandShp", cfg.getLmdDemandShapefile());
        }
        return m;
    }

    /**
     * Records {@code size:lastModified} for a raw input. Content hashing would be stronger but
     * these are multi-hundred-MB gzipped files read on every validate; size+mtime catches the
     * realistic case (an input was re-staged) without adding minutes to startup.
     */
    private static void putSource(Map<String, String> m, String label, String path) {
        Path p = Path.of(path);
        String value;
        try {
            value = Files.exists(p)
                    ? Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis()
                    : "absent";
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat DRT raw input " + p, e);
        }
        m.put(SOURCE_PREFIX + label, value);
    }

    private static TreeSet<String> union(Iterable<String> a, Iterable<String> b) {
        TreeSet<String> all = new TreeSet<>();
        a.forEach(all::add);
        b.forEach(all::add);
        return all;
    }
}
