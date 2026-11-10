package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ShippingPointConfigurationTest {
    @TempDir Path temp;
    @Test void normalizedDirectoryReachesTheRealReaderWithTheCorrectProvider() throws Exception {
        Path points=Files.createDirectories(temp.resolve("points"));
        Files.writeString(points.resolve("dhl_paketnet_list.csv"),"id;x;y\nshop1;500000;5800000\n");
        Path file=Files.writeString(temp.resolve("hagrid.properties"),"paths.shippingPoints=points\nproviders.locationProviders=dhl\n");
        var config=new HagridConfig();config.loadConfiguration(file);
        var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network=scenario.getNetwork();var factory=network.getFactory();
        var a=factory.createNode(Id.createNodeId("a"),new Coord(500000,5800000));
        var b=factory.createNode(Id.createNodeId("b"),new Coord(500100,5800000));
        network.addNode(a);network.addNode(b);network.addLink(factory.createLink(Id.createLinkId("ab"),a,b));
        var reader=new LogisticsDataProcessor();
        for(var name:java.util.List.of("scenario","hagridConfig")) {
            var field=LogisticsDataProcessor.class.getDeclaredField(name);field.setAccessible(true);
            field.set(reader,name.equals("scenario")?scenario:config);
        }
        var hubs=reader.readShippingPoints();
        assertThat(hubs).hasSize(1);
        assertThat(hubs.values().iterator().next().getProvider()).isEqualTo("dhl");
    }
}
