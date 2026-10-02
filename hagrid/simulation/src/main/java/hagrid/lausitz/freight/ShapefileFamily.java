package hagrid.lausitz.freight;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Content identity of a shapefile as the whole family of files GeoTools reads, not the
 * {@code .shp} alone: the geometry is in the {@code .shp}, but every attribute (the PANDA parcel
 * counts among them) is in the {@code .dbf}, and the CRS in the {@code .prj}. Shared by the
 * jsprit result cache key and the DRT prepared-input fingerprint, which used to record the
 * {@code .shp}'s size and mtime only and so missed changed parcel counts (review 2026-10-02 #4).
 */
public final class ShapefileFamily {

    private ShapefileFamily() {
    }

    /**
     * Hashes every file next to the {@code .shp} whose base name matches, ignoring case (shapefiles
     * from Windows shares come as {@code .SHP}/{@code .DBF}). A neighbour such as
     * {@code drt-service-area-with-ruhland.shp} has a different base name and is not included.
     *
     * @return {@code ext:sha256} pairs in extension order, comma-joined
     */
    public static String hash(Path shp) throws IOException {
        if (!Files.isRegularFile(shp)) {
            throw new NoSuchFileException(shp.toString());
        }
        String file = shp.getFileName().toString();
        String base = file.substring(0, file.length() - ".shp".length());
        Map<String, String> byExtension = new TreeMap<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(shp.toAbsolutePath().getParent())) {
            for (Path f : dir) {
                String n = f.getFileName().toString();
                int dot = n.lastIndexOf('.');
                if (dot <= 0 || !Files.isRegularFile(f) || !n.substring(0, dot).equalsIgnoreCase(base)) {
                    continue;
                }
                String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (byExtension.put(ext, Sha256.ofFile(f)) != null) {
                    throw new IllegalStateException("two ." + ext + " files for shapefile " + shp
                            + " that differ only in case - remove one");
                }
            }
        }
        StringJoiner j = new StringJoiner(",");
        byExtension.forEach((ext, hash) -> j.add(ext + ":" + hash));
        return j.toString();
    }
}
