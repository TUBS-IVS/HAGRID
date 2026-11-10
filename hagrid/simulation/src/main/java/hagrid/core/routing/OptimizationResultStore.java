package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.util.CodeFingerprint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.freight.carriers.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.*;

/** Completed single-carrier results, certified by current inputs and a referenced frozen context.
 * One atomic archive publishes payload and manifest together. The supplied request must own the
 * original reproducible context; this store is not a whole-simulation checkpoint.
 */
public final class OptimizationResultStore {
    private static final Logger LOG=LogManager.getLogger(OptimizationResultStore.class);
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String XML="carrier.xml",MANIFEST="manifest.json",VERSION="hagrid-result-v1";
    private final Path directory;
    private final Supplier<Optional<String>> codeFingerprint;
    private final Publisher publisher;
    @FunctionalInterface interface Publisher {void publish(Path temporary,Path completed) throws IOException;}
    public record Manifest(String version,String key,String carrierId,String inputFingerprint,
                           String contextFingerprint,String codeFingerprint,long seed,String xmlDigest,
                           double searchCost,int iterations){}
    public OptimizationResultStore(Path directory){this(directory,CodeFingerprint::ofRunningCode);}
    OptimizationResultStore(Path directory,Supplier<Optional<String>> codeFingerprint){
        this(directory,codeFingerprint,(from,to)->Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
    }
    OptimizationResultStore(Path directory,Supplier<Optional<String>> codeFingerprint,Publisher publisher){
        this.directory=Objects.requireNonNull(directory).toAbsolutePath();
        this.codeFingerprint=Objects.requireNonNull(codeFingerprint);this.publisher=Objects.requireNonNull(publisher);
    }
    public synchronized void save(Carrier carrier,CarrierOptimizationService.Result result,RoutingRequest request,
                                  ResolvedHagridConfig config,long seed){
        requireRequest(carrier,request);
        if(!request.context().config().equals(config))throw new IllegalArgumentException("Result configuration mismatch");
        if(result==null||result.plan()==null||!result.carrierId().equals(carrier.getId().toString())
                ||!result.contextFingerprint().equals(request.context().fingerprint())
                ||!result.diagnostics().isEmpty()||!result.unassignedJobIds().isEmpty()||result.iterations()<1)
            throw new IllegalArgumentException("Only completed results for the current request may be stored");
        var validation=PlanValidator.validate(carrier,result.plan(),request,config);
        if(!validation.valid())throw new IllegalArgumentException("Invalid stored result: "+validation.violations());
        var code=codeFingerprint.get();if(code.isEmpty())return; // preserve disabled IDE cache
        String input=CarrierInputFingerprint.of(carrier),key=key(input,request,config,seed,code.get());
        Path xml=null,archive=null;
        try{
            Files.createDirectories(directory);
            xml=Files.createTempFile(directory,".carrier-",".xml");
            archive=Files.createTempFile(directory,".result-",".pending");
            var detached=CarrierSearchInput.copy(carrier);
            var tours=new ArrayList<ScheduledTour>();
            for(var t:result.plan().getScheduledTours()){
                tours.add(ScheduledTour.newInstance(t.getTour().duplicate(),t.getVehicle(),t.getDeparture()));
                detached.getCarrierCapabilities().getCarrierVehicles().putIfAbsent(t.getVehicle().getId(),t.getVehicle());
            }
            var plan=new CarrierPlan(tours);plan.setCarrier(detached);detached.addPlan(plan);detached.setSelectedPlan(plan);
            PreciseCarrierPlanXml.write(detached,xml);
            var manifest=new Manifest(VERSION,key,carrier.getId().toString(),input,request.context().fingerprint(),
                    code.get(),seed,digest(xml),result.searchCost(),result.iterations());
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){
                zip.putNextEntry(new ZipEntry(XML));Files.copy(xml,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry(MANIFEST));zip.write(JSON.writeValueAsBytes(manifest));zip.closeEntry();
            }
            // Native XML can round or omit values. Certify the actual serialized package before publication.
            var read=read(xml,carrier);
            checkPlan(carrier,read,request,config);
            if(!input.equals(CarrierInputFingerprint.of(carrier)))throw new IllegalStateException("Carrier input changed while storing");
            publisher.publish(archive,directory.resolve(key+".zip"));
        }catch(IOException e){throw new UncheckedIOException("Cannot publish completed optimization result",e);}
        finally{removeOwnedTemporary(xml);removeOwnedTemporary(archive);}
    }
    public synchronized Optional<CarrierOptimizationService.Result> load(Carrier carrier,RoutingRequest request,
                                                                        ResolvedHagridConfig config,long seed){
        requireRequest(carrier,request);
        if(!request.context().config().equals(config))return Optional.empty();
        Path completed=null,xml=null;long started=System.nanoTime();
        try{
            // Certification is optional; unsupported metadata or unavailable code cannot stop a fresh search.
            var code=codeFingerprint.get();if(code.isEmpty())return Optional.empty();
            String input=CarrierInputFingerprint.of(carrier),key=key(input,request,config,seed,code.get());
            completed=directory.resolve(key+".zip");if(!Files.isRegularFile(completed))return Optional.empty();
            try(var zip=new ZipFile(completed.toFile())){
                if(zip.size()!=2||zip.getEntry(XML)==null||zip.getEntry(MANIFEST)==null)throw new IOException("Incomplete package");
                Manifest manifest;
                try(var stream=zip.getInputStream(zip.getEntry(MANIFEST))){manifest=JSON.readValue(stream,Manifest.class);}
                if(!VERSION.equals(manifest.version())||!key.equals(manifest.key())||!input.equals(manifest.inputFingerprint())
                        ||!carrier.getId().toString().equals(manifest.carrierId())
                        ||!request.context().fingerprint().equals(manifest.contextFingerprint())
                        ||!code.get().equals(manifest.codeFingerprint())||manifest.seed()!=seed||manifest.iterations()<1)
                    throw new IOException("Inconsistent manifest");
                xml=Files.createTempFile(directory,".read-",".xml");
                try(var stream=zip.getInputStream(zip.getEntry(XML))){Files.copy(stream,xml,StandardCopyOption.REPLACE_EXISTING);}
                if(!digest(xml).equals(manifest.xmlDigest()))throw new IOException("Result payload checksum mismatch");
                var cached=read(xml,carrier);var validation=checkPlan(carrier,cached,request,config);
                if(!input.equals(CarrierInputFingerprint.of(carrier)))throw new IllegalStateException("Carrier input changed during resume");
                var execution=new CarrierRoutingMetrics.Execution(request.context().fingerprint(),"VALIDATED_CACHE",manifest.iterations(),
                        Optional.empty(),manifest.searchCost(),validation.finalCost(),null,"UNKNOWN");
                var metrics=new CarrierRoutingMetrics(carrier.getId().toString(),"resume",carrier.getServices().size(),
                        carrier.getShipments().size(),0,0,0,"RESUME","CACHE","replanning",Double.NaN,Double.NaN,
                        (System.nanoTime()-started)/1e9,Optional.of(execution));
                return Optional.of(new CarrierOptimizationService.Result(carrier.getId().toString(),cached.getSelectedPlan(),
                        Set.of(),manifest.searchCost(),validation.finalCost(),manifest.iterations(),metrics,
                        request.context().fingerprint(),List.of()).withValidatedCandidate(
                        ValidatedCarrierCandidate.checked(cached.getSelectedPlan(),carrier,request,config,
                                config.tourRules().maxRouteDurationSeconds(),config.tourRules().uTurnPenaltyCost(),validation,input)));
            }
        }catch(IOException|RuntimeException e){
            LOG.warn("Completed result unavailable for carrier {} ({}): {}",carrier.getId(),completed,e.toString());
            return Optional.empty();
        }finally{removeOwnedTemporary(xml);}
    }
    private static Carrier read(Path xml,Carrier carrier){
        var detached=CarrierSearchInput.copy(carrier);var types=new CarrierVehicleTypes();
        detached.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->types.getVehicleTypes().put(v.getType().getId(),v.getType()));
        var carriers=new Carriers();new CarrierPlanXmlReader(carriers,types).readFile(xml.toString());
        var cached=carriers.getCarriers().get(carrier.getId());
        if(cached==null||carriers.getCarriers().size()!=1)throw new IllegalArgumentException("Wrong carrier package");
        cached.getPlans().forEach(plan->plan.setCarrier(cached));
        return cached;
    }
    private static PlanValidator.Validation checkPlan(Carrier carrier,Carrier cached,RoutingRequest request,ResolvedHagridConfig config){
        if(!CarrierInputFingerprint.jobs(carrier).equals(CarrierInputFingerprint.jobs(cached)))
            throw new IllegalArgumentException("Serialized canonical jobs changed");
        var validation=PlanValidator.validate(carrier,cached.getSelectedPlan(),request,config);
        if(!validation.valid())throw new IllegalArgumentException("Stored route is invalid: "+validation.violations());
        return validation;
    }
    private static void requireRequest(Carrier carrier,RoutingRequest request){
        if(request.carrier()!=carrier)throw new IllegalArgumentException("Request belongs to another carrier");
        request.context().requireOpen();
    }
    private static String key(String input,RoutingRequest request,ResolvedHagridConfig config,long seed,String code){
        String base=RoutingData.hashValues(VERSION,input,request.context().fingerprint(),request.preparationMode(),
                config,seed,code,System.getProperty("java.runtime.version"));
        return base;
    }
    static String digest(Path file) throws IOException{
        try{
            var hash=MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))!=-1)hash.update(buffer,0,read);}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void removeOwnedTemporary(Path file){
        if(file!=null)try{Files.deleteIfExists(file);}catch(IOException e){LOG.warn("Cannot remove own temporary result {}: {}",file,e.toString());}
    }
}
