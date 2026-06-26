package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads depot coordinates from a {@code provider;x;y} CSV (EPSG:25832). For DRT
 * only the x;y columns matter — the provider/LSP identity is ignored. Returns
 * coordinates in file order.
 */
public final class DrtDepotReader {

    private DrtDepotReader() {}

    public static List<Coord> readCoords(Path csv) {
        List<Coord> coords = new ArrayList<>();
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
                    coords.add(new Coord(Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed depot row (non-numeric coord): " + line, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (coords.isEmpty()) {
            throw new IllegalArgumentException("no depot coordinates in " + csv);
        }
        return coords;
    }
}
