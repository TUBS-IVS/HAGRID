package hagrid.hannover.demand;

import hagrid.core.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.scenario.MutableScenario;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierGenerationPublicationTest {
    @TempDir Path directory;
    private CarrierGenerator generator(DemandModalFixture f) throws Exception {
        ((MutableScenario)f.scenario).removeScenarioElement("carriers");
        f.scenario.addScenarioElement("deliveries",new LinkedHashMap<String,ArrayList<Delivery>>());
        f.scenario.addScenarioElement("summary",HAGRIDSummary.builder().build());
        var generator=new CarrierGenerator();
        DemandModalFixture.set(generator,"scenario",f.scenario);DemandModalFixture.set(generator,"hagridConfig",f.config);
        return generator;
    }

    @Test void aCompletedEmptyDeliveryGenerationPublishesBothNativeFilesAndItsMergeLog() throws Exception {
        var f=new DemandModalFixture(directory,"truck");var generator=generator(f);
        f.hubs.values().forEach(h->h.setAssignedSupplyDemand(0));
        generator.run();
        assertThat((org.matsim.freight.carriers.Carriers)f.scenario.getScenarioElement("carriers")).isNotNull()
                .satisfies(carriers->assertThat(carriers.getCarriers()).isEmpty());
        assertThat(f.scenario.getScenarioElement("carrierMergeLog")).isNotNull();
        assertThat(Path.of(f.config.io().deliveryCarriersUnrouted())).exists();
        assertThat(Path.of(f.config.io().vehicleTypesOutput())).exists();
    }

    @Test void preExistingMergeLogIsRestoredWhenNativeRegistrationRejectsItsReplacement() throws Exception {
        var f=new DemandModalFixture(directory,"truck");var generator=generator(f);
        var original=new hagrid.hannover.pipeline.CarrierMergeLog();
        f.scenario.addScenarioElement("carrierMergeLog",original);
        assertThatThrownBy(generator::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.scenario.getScenarioElement("carrierMergeLog")).isSameAs(original);
        assertThat(f.scenario.getScenarioElement("carriers")).isNull();
    }
    @Test void lateValidationFailureDoesNotPublishMergeLogOrCarriers() throws Exception {
        var f=new DemandModalFixture(directory,"truck");var generator=generator(f);
        // Empty deliveries deliberately disagree with the pre-existing supply demand.
        assertThatThrownBy(generator::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.scenario.getScenarioElement("carrierMergeLog")).isNull();
        assertThat(f.scenario.getScenarioElement("carriers")).isNull();
        assertThat(Path.of(f.config.io().deliveryCarriersUnrouted())).doesNotExist();
    }

    @Test void failedVehicleTypeOutputKeepsPriorFilesAndScenarioUnregistered() throws Exception {
        var f=new DemandModalFixture(directory,"truck");var generator=generator(f);
        f.hubs.values().forEach(h->h.setAssignedSupplyDemand(0));
        Path delivery=Path.of(f.config.io().deliveryCarriersUnrouted()),types=Path.of(f.config.io().vehicleTypesOutput());
        Files.writeString(delivery,"prior delivery bytes");Files.createDirectories(types);Files.writeString(types.resolve("marker"),"keep");
        assertThatThrownBy(generator::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.scenario.getScenarioElement("carriers")).isNull();
        assertThat(f.scenario.getScenarioElement("carrierMergeLog")).isNull();
        assertThat(Files.readString(delivery)).isEqualTo("prior delivery bytes");
        assertThat(Files.readString(types.resolve("marker"))).isEqualTo("keep");
    }
}