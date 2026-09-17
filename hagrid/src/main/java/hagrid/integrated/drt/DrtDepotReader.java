package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads depot coordinates from a {@code provider;x;y[;site]} CSV (EPSG:25832). Three views:
 * <ul>
 *   <li>{@link #readCoords(Path)} — coordinates only, in file order (DRT fleet anchoring,
 *       where the provider/LSP identity is irrelevant);</li>
 *   <li>{@link #readByProvider(Path)} — provider → coordinate, provider names normalized to
 *       trimmed lowercase (mirrors {@code LmdDepotLoader}); used for the M4(b) provider-depot
 *       assignment of parcel segments.</li>
 *   <li>{@link #readBySite(Path)} — site → coordinate, keyed on the 4th column, for the
 *       INTEGRATED (1c/1d) district-based depot assignment, which names depots by site rather
 *       than by LSP. Requires the {@code site} column; an older provider-only CSV must fail
 *       loudly rather than silently fall back to provider names.</li>
 * </ul>
 */
public final class DrtDepotReader {

    private DrtDepotReader() {}

    public static List<Coord> readCoords(Path csv) {
        List<Coord> coords = new ArrayList<>();
        forEachRow(csv, 0, (provider, coord) -> coords.add(coord));
        if (coords.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return coords;
    }

    /**
     * Reads the depot CSV keeping the provider identity (one depot per LSP; a duplicate
     * provider row overwrites the earlier one, matching {@code LmdDepotLoader}).
     *
     * @param csv path to the {@code provider;x;y} CSV
     * @return provider (trimmed, lowercase) → depot coordinate, in file order
     */
    public static Map<String, Coord> readByProvider(Path csv) {
        Map<String, Coord> depots = new LinkedHashMap<>();
        forEachRow(csv, 0, depots::put);
        if (depots.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return depots;
    }

    /**
     * Reads the depot CSV keyed on the {@code site} column (4th column, index 3), trimmed
     * lowercase, in file order. Used by the INTEGRATED (1c/1d) scenarios, which name depots and
     * districts by site rather than by LSP. An older {@code provider;x;y} CSV without a site
     * column fails loudly instead of silently falling back to provider names.
     *
     * @param csv path to a {@code provider;x;y;site} CSV
     * @return site (trimmed, lowercase) → depot coordinate, in file order
     * @throws IllegalArgumentException if a data row has no site column
     */
    public static Map<String, Coord> readBySite(Path csv) {
        Map<String, Coord> depots = new LinkedHashMap<>();
        forEachRow(csv, 3, depots::put);
        if (depots.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return depots;
    }

    /**
     * Shared row parser: skips header/blank lines, validates, normalizes the key column
     * (trimmed lowercase). {@code keyColumn} 0 = provider, 3 = site.
     */
    private static void forEachRow(Path csv, int keyColumn, java.util.function.BiConsumer<String, Coord> consumer) {
        try {
            List<String> lines = Files.readAllLines(csv);
            int minColumns = Math.max(3, keyColumn + 1);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == 0 && line.toLowerCase().startsWith("provider")) {
                    continue;
                }
                String[] p = line.split(";");
                if (p.length < minColumns) {
                    throw new IllegalArgumentException("depot row has no column " + keyColumn
                            + " (need at least " + minColumns + " columns, e.g. provider;x;y"
                            + (keyColumn >= 3 ? ";site" : "") + "): " + line);
                }
                try {
                    consumer.accept(p[keyColumn].trim().toLowerCase(),
                            new Coord(Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed depot row (non-numeric coord): " + line, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
