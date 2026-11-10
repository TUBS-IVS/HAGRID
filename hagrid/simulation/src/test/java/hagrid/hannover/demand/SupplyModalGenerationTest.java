package hagrid.hannover.demand;
import hagrid.core.util.Hub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SupplyModalGenerationTest {
    @TempDir Path directory;

    @Test void failedSecondSupplyOutputRestoresHubDemandAndPublishesNoScenarioElement() throws Exception {
        var f=new DemandModalFixture(directory,"truck");
        var hub=f.hubs.get(Id.create("hermes-hub",Hub.class));hub.setAssignedSupplyDemand(99);
        Path first=Path.of(f.config.io().supplyCarriersUnrouted()),second=Path.of(f.config.io().supplyCarriersSplitUnrouted());
        var truck=f.types.getVehicleTypes().values().stream().filter(t->t.getNetworkMode().equals("truck")).findFirst().orElseThrow();
        var typeAttributes=new LinkedHashMap<>(truck.getAttributes().getAsMap());
        java.nio.file.Files.writeString(first,"prior supply bytes");java.nio.file.Files.createDirectory(second);
        java.nio.file.Files.writeString(second.resolve("marker"),"keep");
        assertThatThrownBy(()->f.supplyGenerator().run()).isInstanceOf(IllegalStateException.class);
        assertThat(f.scenario.getScenarioElement("supply")).isNull();
        assertThat(hub.getAssignedSupplyDemand()).isEqualTo(99);
        assertThat(truck.getAttributes().getAsMap()).isEqualTo(typeAttributes);
        assertThat(java.nio.file.Files.readString(first)).isEqualTo("prior supply bytes");
        assertThat(java.nio.file.Files.readString(second.resolve("marker"))).isEqualTo("keep");
    }
    @ParameterizedTest @ValueSource(strings={"car","truck"})
    void actualSupplyUsesFinalDemandOnceAndTheActualModeGraph(String mode) throws Exception {
        var f = new DemandModalFixture(directory, mode);
        f.supplyGenerator().run();
        var output=(Carriers)f.scenario.getScenarioElement("supply");
        assertThat(output).isNotNull();
        var hub=f.hubs.get(Id.create("hermes-hub",Hub.class));
        assertThat(hub.getAssignedSupplyDemand()).isEqualTo(4);
        assertThat(output.getCarriers().values().stream().flatMap(c->c.getServices().values().stream())
                .mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(4);
        assertThat(output.getCarriers().values()).allSatisfy(c-> {
            assertThat(CarriersUtils.getCarrierMode(c)).isEqualTo(mode);
            c.getServices().values().forEach(s->assertThat(f.scenario.getNetwork().getLinks().get(s.getServiceLinkId()).getAllowedModes()).contains(mode));
            c.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->assertThat(f.scenario.getNetwork().getLinks().get(v.getLinkId()).getAllowedModes()).contains(mode));
        });
        var reloaded = new Carriers();
        new CarrierPlanXmlReader(reloaded,f.types).readFile(f.config.io().supplyCarriersSplitUnrouted());
        assertThat(reloaded.getCarriers()).hasSize(output.getCarriers().size());
        assertThat(hub.getLink()).isEqualTo(Id.createLinkId("car-stop"));
    }

    @Test void zeroDemandCannotCreateAFullTruckOfPhantomParcels() throws Exception {
        var f=new DemandModalFixture(directory,"car");
        var generator=f.supplyGenerator();
        DemandModalFixture.set(generator,"network",f.scenario.getNetwork());
        DemandModalFixture.set(generator,"supplyVehCap",10);
        DemandModalFixture.set(generator,"durationPerStop",30);
        var c=CarriersUtils.createCarrier(Id.create("zero",Carrier.class));
        DemandModalFixture.invoke(generator,"addSupplyCarrierServices",
                new Class<?>[]{Carrier.class,Hub.class,int.class,String.class,String.class,Id.class},
                c,null,0,"zero","hermes",Id.createLinkId("car-stop"));
        assertThat(c.getServices()).isEmpty();
    }

    @Test void invalidConfiguredDepotCannotPublishSupplyOrXml() throws Exception {
        var f=new DemandModalFixture(directory,"truck");
        for(String dir:List.of("north","south","east","west")) f.config.supply().setLinkDirection(dir,"car-home");
        assertThatIllegalStateException().isThrownBy(f.supplyGenerator()::run).withMessageContaining("supply");
        assertThat(f.scenario.getScenarioElement("supply")).isNull();
        assertThat(Path.of(f.config.io().supplyCarriersUnrouted())).doesNotExist();
        assertThat(Path.of(f.config.io().supplyCarriersSplitUnrouted())).doesNotExist();
    }

    @Test void existingSupplyCannotBeOverwrittenOrChangeHubDemand() throws Exception {
        var f=new DemandModalFixture(directory,"car");
        var existing=new Carriers(); f.scenario.addScenarioElement("supply",existing);
        assertThatIllegalStateException().isThrownBy(f.supplyGenerator()::run).withMessageContaining("supply");
        assertThat(f.scenario.getScenarioElement("supply")).isSameAs(existing);
        assertThat(f.hubs.get(Id.create("hermes-hub",Hub.class)).getAssignedSupplyDemand()).isEqualTo(4);
    }
    @Test void whiteLabelValidationRejectsLostHubParcels() throws Exception {
        var f=new DemandModalFixture(directory,"car");
        assertThatThrownBy(()->DemandModalFixture.invoke(f.supplyGenerator(),"validateWhiteLabelSupplyCarriers",
                new Class<?>[]{Carriers.class,Map.class},new Carriers(),f.hubs))
                .hasMessage("White label supply demand differs for hub hermes-hub: expected 4, got 0");
    }
    @Test void whiteLabelReadsTheConfiguredBaselineAndUsesItsNativeDepot() throws Exception {
        var f=new DemandModalFixture(directory,"truck");
        var baseline=new Carriers();var c=f.carrier("supply_hermes-hub","truck","truck-home");
        c.getAttributes().putAttribute("hubId","hermes-hub");c.getAttributes().putAttribute("supplyLink","truck-home");
        CarriersUtils.addService(c, DemandModalFixture.service("baseline", "truck-stop", 4,
                new org.matsim.api.core.v01.Coord(75,10), 0, 86400));
        baseline.addCarrier(c); Path input=directory.resolve("configured-baseline.xml");
        new CarrierPlanWriter(baseline).write(input.toString());
        f.config.setScenario(hagrid.core.HagridConfig.Scenario.WHITE_LABEL);
        f.config.supply().setBaselineCarriersFile(input.toString());
        f.supplyGenerator().run();
        var result=(Carriers)f.scenario.getScenarioElement("supply");
        assertThat(result.getCarriers().values()).singleElement().satisfies(generated->{
            assertThat(generated.getAttributes().getAttribute("supplyLink")).isEqualTo("truck-home");
            assertThat(generated.getCarrierCapabilities().getCarrierVehicles().values()).allSatisfy(v->
                    assertThat(v.getLinkId()).isEqualTo(Id.createLinkId("truck-home")));
            assertThat(generated.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(4);
        });
    }
}
