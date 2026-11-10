package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.core.util.RunRandomStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.*;

/** Catches seed truncation, wrong precedence and shared/order-dependent random state. */
class RandomSeedSettingsTest {
    @TempDir Path directory;

    @Test void allDomainsShareOneExplicitMasterSeed() {
        var resolved = resolve(Map.of(), Map.of());
        assertThat(resolved.randomSeeds()).isEqualTo(new RandomSeedSettings(1337));
        assertThat(resolved.effectiveValues()).containsEntry("run.seed", "1337");
    }

    @Test void cliMasterSeedOverridesRun() {
        var resolved = resolve(Map.of("run.seed", "11"), Map.of("run.seed", "21"));
        assertThat(resolved.randomSeeds()).isEqualTo(new RandomSeedSettings(21));
        assertThat(resolved.provenance()).containsEntry("run.seed", ResolvedHagridConfig.Source.CLI);
    }

    @Test void rootsPreserveTheFullSignedLongRange() {
        assertThat(resolve(Map.of("run.seed", "-9223372036854775808"), Map.of()).randomSeeds().seed()).isEqualTo(Long.MIN_VALUE);
        assertThat(resolve(Map.of("run.seed", "9223372036854775807"), Map.of()).randomSeeds().seed()).isEqualTo(Long.MAX_VALUE);
        assertThat(resolve(Map.of("run.seed", "0"), Map.of()).randomSeeds().seed()).isZero();
    }

    @Test void malformedOrOverflowingRootFailsDuringResolution() {
        for (String bad : new String[]{"4.2", "NaN", "9223372036854775808", "-9223372036854775809"}) {
            assertThatThrownBy(() -> resolve(Map.of("run.seed", bad), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("run.seed");
        }
    }

    @Test void centralFileAndCanonicalCliUseTheSameSeedSettings() throws Exception {
        Path file = Files.writeString(directory.resolve("hagrid.properties"),
                "run.concept=basecase\nrun.date=2025-05-13\nrun.seed=101\n");
        String original = Files.readString(file);
        var launch = SimulationRunnerUtils.parseScenario("config=" + file.toString().replace('\\', '/')
                + ",seed=202");
        assertThat(launch.getSeed()).isEqualTo(202);
        assertThat(launch.getResolvedRoutingConfig().randomSeeds()).isEqualTo(new RandomSeedSettings(202));
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test void streamsDoNotDependOnCreationOrConsumptionOrder() {
        var first = new RunRandomStreams(new RandomSeedSettings(42));
        var aFirst = first.forKey("demand.weight", "parcel-A");
        var bSecond = first.forKey("demand.weight", "parcel-B");
        long[] a = sample(aFirst);
        long[] b = sample(bSecond);
        var reversed = new RunRandomStreams(new RandomSeedSettings(42));
        var bFirst = reversed.forKey("demand.weight", "parcel-B");
        var aSecond = reversed.forKey("demand.weight", "parcel-A");
        assertThat(sample(bFirst)).containsExactly(b);
        assertThat(sample(aSecond)).containsExactly(a);
    }

    @Test void separateKeysAndDomainsDoNotShareASequence() {
        var streams = new RunRandomStreams(new RandomSeedSettings(42));
        long[] weight = sample(streams.forKey("demand.weight", "A"));
        assertThat(sample(streams.forKey("demand.weight", "B"))).isNotEqualTo(weight);
        assertThat(sample(streams.forKey("demand.missed", "A"))).isNotEqualTo(weight);
        assertThat(sample(streams.forKey("dispatch.departure", "A"))).isNotEqualTo(weight);
    }

    @Test void changingTheMasterSeedChangesEveryDomainsStream() {
        var before = new RunRandomStreams(new RandomSeedSettings(11));
        var after = new RunRandomStreams(new RandomSeedSettings(99));
        assertThat(sample(after.forKey("demand.cluster", "carrier")))
                .isNotEqualTo(sample(before.forKey("demand.cluster", "carrier")));
        assertThat(sample(after.forKey("supply.direction", "hub")))
                .isNotEqualTo(sample(before.forKey("supply.direction", "hub")));
        assertThat(sample(after.forKey("dispatch.departure", "vehicle")))
                .isNotEqualTo(sample(before.forKey("dispatch.departure", "vehicle")));
    }

    @Test void reacquiringAKeyCreatesIndependentStateAtItsStart() {
        var streams = new RunRandomStreams(new RandomSeedSettings(42));
        var consumed = streams.forKey("demand.weight", "parcel");
        long[] initial = sample(consumed);
        sample(consumed);
        assertThat(sample(streams.forKey("demand.weight", "parcel"))).containsExactly(initial);
    }

    @Test void compositeKeysCannotCollideWhenIdsContainSeparators() {
        assertThat(RunRandomStreams.key("a:b", "c")).isNotEqualTo(RunRandomStreams.key("a", "b:c"));
        assertThat(RunRandomStreams.key("ab", "c")).isNotEqualTo(RunRandomStreams.key("a", "bc"));
    }

    @Test void masterSeedControlsSharedUseAndLmdSettings() {
        var resolved = resolve(Map.of("run.seed", "9876543210"), Map.of());
        assertThat(resolved.sharedUse().parcelSeed()).isEqualTo(9876543210L);
        var lmd = LmdPlanningSettings.from(resolved);
        assertThat(lmd.missedDeliverySeed()).isEqualTo(9876543210L);
        assertThat(lmd.tourRetimeSeed()).isEqualTo(9876543210L);
        assertThat(lmd.runSeed()).isEqualTo(9876543210L);
        assertThat(hagrid.core.routing.HAGRIDRouterUtils.resolveSolverSeed(resolved)).isEqualTo(9876543210L);
    }

    @Test void conflictingLegacySeedAliasesFailBeforeGeneration() throws Exception {
        Path file = Files.writeString(directory.resolve("conflicting.properties"),
                "run.seed=101\nlmd.missedDeliverySeed=202\n");
        assertThatThrownBy(() -> HagridConfigFile.load(file))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("run.seed");
    }

    @Test void programmaticSimulationConstructorCarriesTheSameMasterSeed() {
        var launch = new hagrid.core.simulation.HAGRIDSimulationConfig("DRT_BASELINE",
                java.time.LocalDate.of(2025, 5, 13), 1, 1, false, 0, 0, "",
                hagrid.core.util.StudyArea.LAUSITZ_HOYERSWERDA, 4, false, false, 600, false, 20260728L);
        assertThat(launch.getResolvedRoutingConfig().randomSeeds().seed()).isEqualTo(20260728L);
    }

    private static ResolvedHagridConfig resolve(Map<String, String> run, Map<String, String> cli) {
        return HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.of(run), cli);
    }

    private static long[] sample(RandomGenerator random) {
        return new long[]{random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()};
    }
}
