package hagrid;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import hagrid.demand.CarrierGenerator;
import hagrid.demand.DeliveryGenerator;
import hagrid.demand.DemandProcessor;
import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class HagridModule extends AbstractModule {

    private final String configFilePath;
    private Config config;
    private Scenario scenario;

    public HagridModule(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    @Override
    protected void configure() {
        this.config = ConfigUtils.loadConfig(configFilePath);
        this.scenario = ScenarioUtils.loadScenario(config);
        this.scenario.addScenarioElement(HagridConfigGroup.GROUPNAME, ConfigUtils.addOrGetModule(config, HagridConfigGroup.class));

        // Bind NetworkProcessor as a singleton.
        bind(NetworkProcessor.class).in(Singleton.class);
        // Bind LogisticsDataProcessor as a singleton.
        bind(LogisticsDataProcessor.class).in(Singleton.class);
        // Bind DemandProcessor as a singleton.
        bind(DemandProcessor.class).in(Singleton.class);
        // Bind DeliveryGenerator as a singleton.
        bind(DeliveryGenerator.class).in(Singleton.class);
        // Bind CarrierGenerator as a singleton.
        bind(CarrierGenerator.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    public Config provideConfig() {
        return this.config;
    }

    @Provides
    @Singleton
    public Scenario provideScenario() {
        return this.scenario;
    }

    @Provides
    @Singleton
    public HagridConfigGroup provideHagridConfigGroup() {
        return ConfigUtils.addOrGetModule(this.config, HagridConfigGroup.class);
    }
}
