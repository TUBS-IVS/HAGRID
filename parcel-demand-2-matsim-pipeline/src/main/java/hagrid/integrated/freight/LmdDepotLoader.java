package hagrid.integrated.freight;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the synthetic LMD depot CSV (one row per LSP) and snaps each depot to the nearest
 * car link. CSV format: header {@code provider;x;y}, coordinates in EPSG:25832.
 */
public final class LmdDepotLoader {

    /** The seven LSPs modelled in the Lausitz LMD baseline. */
    public static final Set<String> PROVIDERS =
            Set.of("dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex");

    private LmdDepotLoader() {}

    public static Map<String, Id<Link>> load(String csvPath, Network network) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(csvPath));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read LMD depot CSV: " + csvPath, e);
        }

        Map<String, Id<Link>> depots = new LinkedHashMap<>();
        boolean headerSkipped = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue; // skip blank lines
            }
            if (!headerSkipped) {
                headerSkipped = true;
                continue; // skip first non-blank line (header)
            }
            String[] parts = line.split(";");
            if (parts.length < 3) {
                throw new IllegalStateException("Malformed LMD depot row (need provider;x;y): " + line);
            }
            String provider = parts[0].trim().toLowerCase();
            if (!PROVIDERS.contains(provider)) {
                throw new IllegalStateException("Unknown LMD provider in depot CSV: " + provider);
            }
            double x;
            double y;
            try {
                x = Double.parseDouble(parts[1].trim());
                y = Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Non-numeric coordinate for provider " + provider + ": " + line, e);
            }
            Link link = NetworkUtils.getNearestLinkExactly(network, new Coord(x, y));
            if (link == null) {
                throw new IllegalStateException("No network link near depot for " + provider);
            }
            depots.put(provider, link.getId());
        }

        if (depots.isEmpty()) {
            throw new IllegalStateException("LMD depot CSV contained no depot rows: " + csvPath);
        }
        return depots;
    }
}
