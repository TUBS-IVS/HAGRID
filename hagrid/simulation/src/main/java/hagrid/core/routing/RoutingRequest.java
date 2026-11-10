package hagrid.core.routing;

import org.matsim.freight.carriers.Carrier;
import java.util.*;

/** Binds one single-mode carrier to a frozen context and an explicit neutral preparation mode. */
public record RoutingRequest(Carrier carrier, RoutingContext context, String preparationMode) {
    public RoutingRequest {
        Objects.requireNonNull(carrier,"carrier"); Objects.requireNonNull(context,"context");
        if(preparationMode==null || preparationMode.isBlank()) throw new IllegalArgumentException("Preparation mode is required");
        context.requireOpen(); context.snapshot().forMode(preparationMode);
        var modes=new HashSet<String>();
        var types=new HashSet<>(carrier.getCarrierCapabilities().getVehicleTypes());
        carrier.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->types.add(v.getType()));
        for(var type:types) {
            var profile=context.vehicleProfile(type.getId());
            ModalTransportCosts.validateType(type,profile);
            modes.add(profile.mode());
        }
        if(modes.size()>1) throw new IllegalArgumentException("Carrier "+carrier.getId()+" has unsupported mixed network modes "+modes);
        if(!modes.isEmpty() && !modes.contains(preparationMode)) throw new IllegalArgumentException("Carrier "+carrier.getId()+" mode disagrees with preparation mode "+preparationMode);
    }
}