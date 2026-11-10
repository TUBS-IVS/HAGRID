package hagrid.core.routing.sections;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionBindingValidatorTest {
 @Test void rejectsUnauthorizedAndMissingBindingsAndChangedDuration() {
  var original=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  var job=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("raw"),3)
   .setServiceDuration(42).setServiceStartingTimeWindow(TimeWindow.newInstance(10,100)).build();
  CarriersUtils.addService(original,job);
  var variant=new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("halt")));
  var catalog=new SectionCatalog(original,List.of(new DeliverySection("s",List.of("j"),3,List.of(variant))),"x");
  var projected=CarriersUtils.createCarrier(original.getId());
  var projectedJob=CarrierService.Builder.newInstance(job.getId(),Id.createLinkId("halt"),3)
   .setServiceDuration(42).setServiceStartingTimeWindow(job.getServiceStaringTimeWindow()).build();
  CarriersUtils.addService(projected,projectedJob);
  assertThat(SectionBindingValidator.validateProjection(catalog,projected,Map.of("j",Id.createLinkId("halt")))).isEmpty();
  assertThat(SectionBindingValidator.validateProjection(catalog,projected,Map.of())).isNotEmpty();
  assertThat(SectionBindingValidator.validateProjection(catalog,projected,Map.of("j",Id.createLinkId("evil")))).isNotEmpty();
  projected.getServices().clear();
  CarriersUtils.addService(projected,CarrierService.Builder.newInstance(job.getId(),Id.createLinkId("halt"),3)
   .setServiceDuration(43).setServiceStartingTimeWindow(job.getServiceStaringTimeWindow()).build());
  assertThat(SectionBindingValidator.validateProjection(catalog,projected,Map.of("j",Id.createLinkId("halt")))).isNotEmpty();
 }
}
