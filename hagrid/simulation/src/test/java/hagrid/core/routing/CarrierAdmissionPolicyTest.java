package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.simulation.ReplanningCoordinator;
import hagrid.hannover.demand.Router;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class CarrierAdmissionPolicyTest {
    @TempDir Path directory;
    private static final String KEY="resources.carrierAdmissionPolicyFile";
    private static ResolvedHagridConfig.Resources resources(int workers,int internal){
        return new ResolvedHagridConfig.Resources(internal,OptionalInt.of(workers));
    }
    private static Carrier carrier(String id){return CarriersUtils.createCarrier(Id.create(id,Carrier.class));}
    private Path policy(Carrier... carriers)throws Exception {
        var entries=new LinkedHashMap<String,Object>();
        for(var c:carriers)entries.put(c.getId().toString(),Map.of("inputFingerprint",CarrierInputFingerprint.of(c),"reservedBytes",60));
        var content=new LinkedHashMap<String,Object>();
        content.put("schemaVersion",1);content.put("maxActiveSolvers",2);content.put("byteBudget",120);
        content.put("heapReserveBytes",1);content.put("physicalReserveBytes",1);content.put("nativeHeadroomBytes",1);
        content.put("measurement","Synthetic unit fixture; not a production calibration");content.put("carriers",entries);
        Path path=directory.resolve("policy.json");new ObjectMapper().writeValue(path.toFile(),content);return path;
    }
    @Test void noPolicyRetainsSerialCompatibilityDespiteHigherRequestedCap(){
        try(var admission=CarrierAdmissionPolicy.open(Map.of(),resources(4,1))){
            var jobs=List.of(new CarrierBatchExecutor.Descriptor("a",0,0,0,1,0),new CarrierBatchExecutor.Descriptor("b",0,0,0,2,0));
            var result=new ArrayList<String>();admission.executor().runStaged(jobs,CarrierBatchExecutor.Descriptor::carrierId,result::add,()->{});
            assertThat(result).containsExactly("a","b");assertThat(admission.executor().createdWorkers()).isEqualTo(1);
        }
    }
    @Test void configuredPolicyUsesAvailableWorkersAndPublishesInInputOrder()throws Exception {
        int expectedWorkers=Math.min(2,Runtime.getRuntime().availableProcessors());
        var a=carrier("a");var b=carrier("b");var path=policy(a,b);var together=new CountDownLatch(expectedWorkers);
        CarrierBatchExecutor executor;
        try(var admission=CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(4,1))){
            executor=admission.executor();var result=new ArrayList<String>();
            var jobs=List.of(new CarrierBatchExecutor.Descriptor("a",0,0,0,1,admission.reservedBytes().applyAsLong(a)),
                    new CarrierBatchExecutor.Descriptor("b",0,0,0,2,admission.reservedBytes().applyAsLong(b)));
            assertThatCode(()->executor.runStaged(jobs,d->{together.countDown();try{if(!together.await(3,TimeUnit.SECONDS))throw new AssertionError("Serial execution");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}return d.carrierId();},result::add,
                ()->assertThat(result).containsExactly("a","b"))).doesNotThrowAnyException();
            assertThat(executor.createdWorkers()).isEqualTo(expectedWorkers);assertThat(executor.reservedBytes()).isZero();
        }
        assertThatThrownBy(()->executor.run(List.of(),d->d,r->{})).hasMessageContaining("unavailable");
    }
    @Test void staleOrMissingCarrierEstimateFailsInsteadOfUsingAnInventedDefault()throws Exception {
        var a=carrier("a");var path=policy(a);
        try(var admission=CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(2,1))){
            assertThat(admission.reservedBytes().applyAsLong(a)).isEqualTo(60);
            assertThatThrownBy(()->admission.reservedBytes().applyAsLong(carrier("b"))).hasMessageContaining("b");
            CarriersUtils.addService(a,CarrierService.Builder.newInstance(Id.create("new",CarrierService.class),Id.createLinkId("road")).build());
            assertThatThrownBy(()->admission.reservedBytes().applyAsLong(a)).hasMessageContaining("fingerprint");
            assertThat(admission.executor().createdWorkers()).isZero();
        }
    }
    @Test void wholePublicationGroupMustFitBeforeAnyWorkStarts()throws Exception {
        var a=carrier("a");var path=policy(a);
        try(var admission=CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(2,1))){
            var jobs=List.of(new CarrierBatchExecutor.Descriptor("a",0,0,0,1,60),new CarrierBatchExecutor.Descriptor("b",0,0,0,2,61));
            assertThatThrownBy(()->admission.executor().runStaged(jobs,d->{throw new AssertionError("Started over budget");},r->{},()->{}))
                .hasMessageContaining("budget");assertThat(admission.executor().createdWorkers()).isZero();
        }
    }
    @Test void nestedSolverPoolsAreRejectedForParallelAdmission()throws Exception {
        var path=policy(carrier("a"));
        assertThatThrownBy(()->CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(2,2))).hasMessageContaining("internalSolverThreads");
    }
    @Test void malformedOrUnsupportedPolicyNeverSilentlyFallsBack()throws Exception {
        Path path=directory.resolve("invalid.json");Files.writeString(path,"{\"schemaVersion\":2}");
        assertThatThrownBy(()->CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(2,1))).isInstanceOf(RuntimeException.class);
        Files.writeString(path,"{bad");
        assertThatThrownBy(()->CarrierAdmissionPolicy.open(Map.of(KEY,path.toString()),resources(2,1))).isInstanceOf(RuntimeException.class);
    }
    @Test void fileSettingResolvesAgainstTheConfigurationDirectory()throws Exception {
        Path config=directory.resolve("run.properties");Files.writeString(config,KEY+"=policy.json\n");
        assertThatCode(()->HagridConfigFile.load(config)).doesNotThrowAnyException();
        assertThat(HagridConfigFile.load(config).canonicalValues()).containsEntry(KEY,directory.resolve("policy.json").toString());
    }
    @Test void actualInitialAndReplanningOwnersConsumeThePolicy(){
        String missing=directory.resolve("missing-policy.json").toString();var config=new HagridConfig();
        config.setAdditionalConfigurationValues(Map.of(KEY,missing));config.resources().setMaxActiveSolvers(2);
        assertThatThrownBy(()->new Router(ThreadingType.COMPLETABLE_FUTURE,null,config,new CarrierVehicleTypes()))
                .hasMessageContaining("policy");
        var resolved=HagridConfigResolver.resolve(config,HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningIterations","1","optimization.replanningStartIteration","1","optimization.carriersPerGroup","2")),Map.of());
        assertThatThrownBy(()->new ReplanningCoordinator(resolved,42)).hasMessageContaining("policy");
    }
}
