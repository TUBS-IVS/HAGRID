package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.freight.carriers.Carrier;
import java.nio.file.Path;
import java.util.*;

/** Small test networks only; these byte values are not production recommendations. */
public final class CarrierAdmissionTestPolicies {
    private CarrierAdmissionTestPolicies(){}
    public static void write(Path path,Collection<Carrier> carriers)throws Exception {
        var entries=new LinkedHashMap<String,Object>();
        for(var carrier:carriers)entries.put(carrier.getId().toString(),Map.of(
            "inputFingerprint",CarrierInputFingerprint.of(carrier),"reservedBytes",16L*1024*1024));
        new ObjectMapper().writeValue(path.toFile(),Map.of("schemaVersion",1,"maxActiveSolvers",2,
            "byteBudget",64L*1024*1024,"heapReserveBytes",16L*1024*1024,
            "physicalReserveBytes",16L*1024*1024,"nativeHeadroomBytes",16L*1024*1024,
            "measurement","Synthetic tiny-network integration fixture","carriers",entries));
    }
}
