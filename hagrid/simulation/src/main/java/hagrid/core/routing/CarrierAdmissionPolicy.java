package hagrid.core.routing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import hagrid.core.config.ResolvedHagridConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.freight.carriers.Carrier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Run-owned, explicit memory reservations. A policy is read once, never from replay restoration.
 * Estimates include construction, search, final routing, validation and staged results; they are
 * not a memory watchdog or an OOM guarantee. Shared context/native-cache growth needs reserves too.
 */
public final class CarrierAdmissionPolicy {
    public static final String FILE_SETTING="resources.carrierAdmissionPolicyFile";
    private static final Logger LOG=LogManager.getLogger(CarrierAdmissionPolicy.class);
    private CarrierAdmissionPolicy() {}

    public record Admission(CarrierBatchExecutor executor,ToLongFunction<Carrier> reservedBytes)
            implements AutoCloseable {
        public Admission {Objects.requireNonNull(executor);Objects.requireNonNull(reservedBytes);}
        @Override public void close(){executor.close();}
    }
    private record Reservation(String inputFingerprint,long reservedBytes) {
        private Reservation {
            if(inputFingerprint==null||!inputFingerprint.matches("[0-9a-f]{64}")||reservedBytes<1)
                throw new IllegalArgumentException("Policy requires a SHA-256 input fingerprint and positive reservedBytes");
        }
    }
    private record Policy(int schemaVersion,int maxActiveSolvers,long byteBudget,
                          long heapReserveBytes,long physicalReserveBytes,long nativeHeadroomBytes,
                          String measurement,Map<String,Reservation> carriers) {
        private Policy {
            if(schemaVersion!=1||maxActiveSolvers<1||byteBudget<1||heapReserveBytes<0
                    ||physicalReserveBytes<0||nativeHeadroomBytes<0||measurement==null||measurement.isBlank()
                    ||carriers==null||carriers.isEmpty())
                throw new IllegalArgumentException("Invalid carrier admission policy: version, limits, measurement and carriers are required");
            carriers=Map.copyOf(carriers);
            for(var entry:carriers.entrySet())
                if(entry.getKey().isBlank()||entry.getValue().reservedBytes()>byteBudget)
                    throw new IllegalArgumentException("Invalid carrier reservation in policy: "+entry.getKey());
        }
    }

    /** For calibration tools: capture prepared demand/fleet inputs, excluding previous plans. */
    public static String inputFingerprint(Carrier carrier){return CarrierInputFingerprint.of(carrier);}

    public static Admission open(Map<String,String> values,ResolvedHagridConfig.Resources resources) {
        Objects.requireNonNull(values);Objects.requireNonNull(resources);
        String input=values.get(FILE_SETTING);
        if(input==null) {
            if(resources.maxActiveSolvers().orElse(1)>1)
                LOG.warn("No {} supplied; retaining serial carrier admission",FILE_SETTING);
            return new Admission(new CarrierBatchExecutor(),carrier->0);
        }
        Path file=Path.of(input).toAbsolutePath().normalize();
        final Policy policy;
        try {
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            policy=mapper.readValue(file.toFile(),Policy.class);
            if(policy==null)throw new IllegalArgumentException("Null carrier admission policy: "+file);
        } catch(IOException invalid) {
            throw new UncheckedIOException("Cannot read carrier admission policy: "+file,invalid);
        }
        int requested=Math.min(resources.maxActiveSolvers().orElse(1),policy.maxActiveSolvers());
        if(requested>1&&resources.internalSolverThreads()!=1)
            throw new IllegalArgumentException("Parallel carrier admission requires resources.internalSolverThreads=1");
        int workers=Math.min(requested,Math.max(1,Runtime.getRuntime().availableProcessors()/resources.internalSolverThreads()));
        var memory=CarrierRunBudget.forCurrentJvm(()->{
            var os=ManagementFactory.getOperatingSystemMXBean();
            if(!(os instanceof com.sun.management.OperatingSystemMXBean nativeOs))
                throw new IllegalStateException("Physical memory observation unavailable for carrier admission");
            long free=nativeOs.getFreeMemorySize();
            if(free<=0)throw new IllegalStateException("Invalid physical memory observation for carrier admission");
            return free;
        },policy.physicalReserveBytes(),policy.heapReserveBytes(),policy.nativeHeadroomBytes());
        var executor=new CarrierBatchExecutor(workers,()->Math.min(policy.byteBudget(),memory.getAsLong()),Duration.ofSeconds(60));
        LOG.info("Carrier admission policy {}: worker ceiling={}, reservation ceiling={} bytes, carriers={}",
                file,workers,policy.byteBudget(),policy.carriers().size());
        return new Admission(executor,carrier->{
            String id=carrier.getId().toString();var reservation=policy.carriers().get(id);
            if(reservation==null)throw new IllegalArgumentException("Missing carrier admission reservation: "+id);
            if(!reservation.inputFingerprint().equals(inputFingerprint(carrier)))
                throw new IllegalArgumentException("Carrier admission input fingerprint changed: "+id);
            return reservation.reservedBytes();
        });
    }
}
