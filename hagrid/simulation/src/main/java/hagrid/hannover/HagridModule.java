package hagrid.hannover;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import hagrid.core.HagridConfig;
import hagrid.hannover.demand.*;
import hagrid.hannover.pipeline.PipelineStepException;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Demand-only injector: validates inputs first and lets NetworkProcessor own the network import. */
public class HagridModule extends AbstractModule {
    private final Config config;
    private final HagridConfig hagridConfig;
    private Scenario scenario;

    public HagridModule(String configFilePath) {
        this(configFilePath, new HagridConfig());
    }

    public HagridModule(String configFilePath, HagridConfig effectiveConfig) {
        hagridConfig = Objects.requireNonNull(effectiveConfig, "effective HAGRID configuration");
        try {
            config = ConfigUtils.loadConfig(configFilePath);
            String xmlInput = config.network().getInputFile();
            if (xmlInput != null && !xmlInput.isBlank()) {
                Path xml = canonical(config.network().getInputFileURL(config.getContext()));
                if (hagridConfig.paths().isNetworkExplicit()) {
                    Path hagrid = canonical(Path.of(hagridConfig.getNetworkXmlPath()));
                    if (!xml.equals(hagrid)) {
                        throw new IllegalArgumentException("Conflicting network inputs: MATSim=" + xml + ", HAGRID=" + hagrid);
                    }
                }
                hagridConfig.paths().setNetwork(xml.toString());
            }
            // The master seed is effective before the Scenario and its consumers are created.
            config.global().setRandomSeed(hagridConfig.randomSeeds().seed());
        } catch (Exception failure) {
            throw new PipelineStepException("Configuration", failure);
        }
    }

    private static Path canonical(URL input) throws Exception {
        if (!"file".equalsIgnoreCase(input.getProtocol())) {
            throw new IllegalArgumentException("Demand network must be a local file: " + input);
        }
        return canonical(Path.of(input.toURI()));
    }

    private static Path canonical(Path input) throws java.io.IOException {
        Path path = input.toAbsolutePath().normalize();
        return Files.exists(path) ? path.toRealPath() : path;
    }

    @Override protected void configure() {
        scenario = ScenarioUtils.createScenario(config);
        scenario.addScenarioElement("hagridConfig", hagridConfig);
        bind(NetworkProcessor.class).in(Singleton.class);
        bind(LogisticsDataProcessor.class).in(Singleton.class);
        bind(DemandProcessor.class).in(Singleton.class);
        bind(DeliveryGenerator.class).in(Singleton.class);
        bind(CarrierGenerator.class).in(Singleton.class);
    }

    @Provides @Singleton public Config provideConfig() { return config; }
    @Provides @Singleton public Scenario provideScenario() { return scenario; }
    @Provides @Singleton public HagridConfig provideHagridConfig() { return hagridConfig; }
}
