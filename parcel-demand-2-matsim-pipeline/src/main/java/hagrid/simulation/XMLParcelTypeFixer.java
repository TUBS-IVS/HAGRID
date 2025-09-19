package hagrid.simulation;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public class XMLParcelTypeFixer {
    /**
     * Replaces all <attribute name="type">Mixed</attribute> with <attribute name="type">MIXED</attribute> in the given XML file.
     * @param xmlPath Path to the XML file
     * @throws IOException if file cannot be read or written
     */
    public static void fixMixedTypeInFile(String xmlPath) throws IOException {
    Path path = Paths.get(xmlPath);
    // Create backup before modifying
    Path backupPath = Paths.get(xmlPath + ".bak");
    Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
    String content = Files.readString(path, StandardCharsets.UTF_8);
        String fixed = content.replaceAll(
            "<attribute name=\"type\"[^>]*>Mixed</attribute>",
            "<attribute name=\"type\" class=\"hagrid.utils.demand.Delivery$ParcelType\">MIXED</attribute>"
        );
        Files.writeString(path, fixed, StandardCharsets.UTF_8);
    }
}
