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
 * Reads depot coordinates from a {@code provider;x;y} CSV (EPSG:25832). Two views:
 * <ul>
 *   <li>{@link #readCoords(Path)} — coordinates only, in file order (DRT fleet anchoring,
 *       where the provider/LSP identity is irrelevant);</li>
 *   <li>{@link #readByProvider(Path)} — provider → coordinate, provider names normalized to
 *       trimmed lowercase (mirrors {@code LmdDepotLoader}); used for the M4(b) provider-depot
 *       assignment of parcel segments.</li>
 * </ul>
 */
public final class DrtDepotReader {

    private DrtDepotReader() {}

    public static List<Coord> readCoords(Path csv) {
        List<Coord> coords = new ArrayList<>();
        forEachRow(csv, (provider, coord) -> coords.add(coord));
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
        forEachRow(csv, depots::put);
        if (depots.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return depots;
    }

    /** Shared row parser: skips header/blank lines, validates, normalizes the provider name. */
    private static void forEachRow(Path csv, java.util.function.BiConsumer<String, Coord> consumer) {
        try {
            List<String> lines = Files.readAllLines(csv);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == 0 && line.toLowerCase().startsWith("provider")) {
                    continue;
                }
                String[] p = line.split(";");
                if (p.length < 3) {
                    throw new IllegalArgumentException("malformed depot row: " + line);
                }
                try {
                    consumer.accept(p[0].trim().toLowerCase(),
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
