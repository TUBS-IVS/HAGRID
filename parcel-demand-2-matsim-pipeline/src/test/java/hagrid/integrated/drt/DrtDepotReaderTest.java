package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtDepotReader")
class DrtDepotReaderTest {

    @Test
    @DisplayName("reads provider;x;y rows into coords, skipping header and blank lines")
    void readsCoords(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("depots.csv");
        Files.writeString(csv, "provider;x;y\ndhl;100.0;200.0\namazon;300.5;400.5\n\n");
        List<Coord> coords = DrtDepotReader.readCoords(csv);
        assertThat(coords).hasSize(2);
        assertThat(coords.get(0)).isEqualTo(new Coord(100.0, 200.0));
        assertThat(coords.get(1)).isEqualTo(new Coord(300.5, 400.5));
    }

    @Test
    @DisplayName("rejects a file with no data rows")
    void rejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("e.csv");
        Files.writeString(csv, "provider;x;y\n");
        assertThatThrownBy(() -> DrtDepotReader.readCoords(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a file with a non-numeric coordinate")
    void rejectsMalformedRow(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("bad.csv");
        Files.writeString(csv, "provider;x;y\ndhl;notanumber;200.0\n");
        assertThatThrownBy(() -> DrtDepotReader.readCoords(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("M4(b): readByProvider keeps the provider identity, normalized to trimmed lowercase")
    void readsByProvider(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("depots.csv");
        Files.writeString(csv, "provider;x;y\nDHL ;100.0;200.0\namazon;300.5;400.5\n\n");
        Map<String, Coord> depots = DrtDepotReader.readByProvider(csv);
        assertThat(depots).hasSize(2);
        assertThat(depots.get("dhl")).isEqualTo(new Coord(100.0, 200.0));
        assertThat(depots.get("amazon")).isEqualTo(new Coord(300.5, 400.5));
    }

    @Test
    @DisplayName("readByProvider rejects a file with no data rows")
    void readByProviderRejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("e.csv");
        Files.writeString(csv, "provider;x;y\n");
        assertThatThrownBy(() -> DrtDepotReader.readByProvider(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readByProvider rejects a non-numeric coordinate")
    void readByProviderRejectsMalformedRow(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("bad.csv");
        Files.writeString(csv, "provider;x;y\ndhl;notanumber;200.0\n");
        assertThatThrownBy(() -> DrtDepotReader.readByProvider(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readBySite keys on the 4th column, normalized to trimmed lowercase, file order preserved")
    void readsBySite(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("depots.csv");
        Files.writeString(csv, "provider;x;y;site\n"
                + "dhl;866341.8;5705764.6;wittichenau\n"
                + "amazon;855395.1;5712299.2; LAUTA \n"
                + "hermes;867545.1;5710992.6;hoy_sued\n");
        Map<String, Coord> depots = DrtDepotReader.readBySite(csv);
        assertThat(depots.keySet()).containsExactly("wittichenau", "lauta", "hoy_sued");
        assertThat(depots.get("wittichenau")).isEqualTo(new Coord(866341.8, 5705764.6));
        assertThat(depots.get("lauta")).isEqualTo(new Coord(855395.1, 5712299.2));
        assertThat(depots.get("hoy_sued")).isEqualTo(new Coord(867545.1, 5710992.6));
    }

    @Test
    @DisplayName("readBySite rejects a file with no data rows")
    void readBySiteRejectsEmpty(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("e.csv");
        Files.writeString(csv, "provider;x;y;site\n");
        assertThatThrownBy(() -> DrtDepotReader.readBySite(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readBySite rejects a non-numeric coordinate")
    void readBySiteRejectsMalformedRow(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("bad.csv");
        Files.writeString(csv, "provider;x;y;site\ndhl;notanumber;200.0;wittichenau\n");
        assertThatThrownBy(() -> DrtDepotReader.readBySite(csv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readBySite fails loudly on an older provider-only CSV missing the site column")
    void readBySiteRejectsMissingSiteColumn(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("old.csv");
        Files.writeString(csv, "provider;x;y\ndhl;866341.8;5705764.6\n");
        assertThatThrownBy(() -> DrtDepotReader.readBySite(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("site");
    }
}
