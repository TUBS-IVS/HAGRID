package hagrid;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
