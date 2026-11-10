package hagrid.hannover;

import hagrid.core.config.HagridConfigFile;
import hagrid.hannover.pipeline.ScenarioConfig;
import hagrid.hannover.pipeline.ScenarioRunner;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/** One explicitly configured demand/routing run. Does not launch MATSim or generate a simulation script. */
public final class HagridV2RoutingRunner {
    private HagridV2RoutingRunner() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: HagridV2RoutingRunner <hagrid.properties>");
        }
        new ScenarioRunner(scenario(Path.of(args[0]))).runAll();
    }

    static ScenarioConfig scenario(Path path) {
        var file = HagridConfigFile.load(path);
        var values = file.values();
        String concept = required(values, "run.concept");
        if (!concept.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("run.concept must name exactly one scenario");
        }
        var date = LocalDate.parse(required(values, "run.date"));
        return ScenarioConfig.builder()
                .concepts(concept).dates(date).tag(values.getOrDefault("run.tag", "v2"))
                .configurationFile(file.path())
                .runRouting(true).applyServiceSimplifier(true).enableCaching(false)
                .build();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required setting: " + key);
        }
        return value;
    }
}
