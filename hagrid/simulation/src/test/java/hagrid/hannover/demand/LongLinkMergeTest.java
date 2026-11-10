package hagrid.hannover.demand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
class LongLinkMergeTest {
 @TempDir Path dir;
 private CarrierService job(String id, double x, String stop, Object street, String section) {
  var s=DemandModalFixture.service(id,"car-stop",1,new Coord(x,0),0,86400);
  if(stop!=null)s.getAttributes().putAttribute("stop_id",stop);
  if(street!=null)s.getAttributes().putAttribute("str_idx",street);
  if(section!=null)s.getAttributes().putAttribute("section_id",section);
  return s;
 }
 private Carrier run(boolean full, CarrierService... jobs) throws Exception {
  var f=new DemandModalFixture(dir,"bike"); var c=f.carrier("dhl_12345_car","car","car-home");
  for(var s:jobs)CarriersUtils.addService(c,s);
  var carriers=new Carriers();carriers.addCarrier(c);CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,full);return c;
 }
 @Test void nonFullDistantLegacyCoordinatesStayDistinct() throws Exception {
  assertThat(run(false,job("a",0,null,null,null),job("b",5000,null,null,null)).getServices()).hasSize(2);
 }
 @Test void differentStopsAndKnownUnknownNeverMergeEvenAtEqualCoordinates() throws Exception {
  for(boolean full:new boolean[]{false}) {
   assertThat(run(full,job("a",75,"a",1,"1-0-0"),job("b",75,"b",1,"1-0-0")).getServices()).hasSize(2);
   assertThat(run(full,job("a",75,"a",1,"1-0-0"),job("b",75,null,1,"1-0-0")).getServices()).hasSize(2);
  }
 }
 @Test void differentSectionsNeverMergeEvenForSameStop() throws Exception {
  assertThat(run(false,job("a",75,"a",1,"1-0-0"),job("b",75,"a",1,"1-0-1")).getServices()).hasSize(2);
 }
 @Test void equivalentNumericStreetIndicesMerge() throws Exception {
  var c=run(false,job("a",75,"a",1,"1-0-0"),job("b",75,"a",1L,"1-0-0"),
    job("c",75,"a",1.0,"1-0-0"),job("d",75,"a","1","1-0-0"),job("e",75,"a","1.0","1-0-0"));
  assertThat(c.getServices().values()).singleElement().satisfies(s -> assertThat(s.getCapacityDemand()).isEqualTo(5));
 }
 @Test void fullMergeKeepsNearbyStopsAndTheirTravelAllowanceWithoutMislabeling() throws Exception {
  var c=run(true,job("a",0,"left",7,"7-0-0"),job("b",100,"right",7,"7-0-1"));
  assertThat(c.getServices().values()).singleElement().satisfies(s -> {
   assertThat(s.getCapacityDemand()).isEqualTo(2);
   assertThat(s.getServiceDuration()).isGreaterThan(60);
   assertThat(s.getAttributes().getAttribute("stop_id")).isNull();
   assertThat(s.getAttributes().getAttribute("section_id")).isNull();
   assertThat(s.getAttributes().getAttribute("str_idx")).isEqualTo("7");
   assertThat(s.getAttributes().getAttribute("mergedMetadata").toString()).contains("left","right","7-0-0","7-0-1");
  });
 }
 @Test void duplicateStopMergesAndRetainsProvenance() throws Exception {
  var c=run(true,job("a",75,"a",1,"1-0-0"),job("b",75,"a","1","1-0-0"));
  assertThat(c.getServices().values()).singleElement().satisfies(s->{
   assertThat(s.getCapacityDemand()).isEqualTo(2);
   assertThat(s.getAttributes().getAttribute("stop_id")).isEqualTo("a");
   assertThat(s.getAttributes().getAttribute("str_idx").toString()).isEqualTo("1");
   assertThat(s.getAttributes().getAttribute("section_id")).isEqualTo("1-0-0");
   assertThat(s.getAttributes().getAttribute("mergedMetadata").toString()).contains("stop_id","section_id");
  });
 }
}