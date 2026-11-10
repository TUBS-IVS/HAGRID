package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.CarrierService;
final class SectionMemberIndex {
 record Variant(int index,int[] slots,List<Id<Link>> halts) {}
 record Section(String id,int demand,int[] slots,List<Variant> variants) {}
 private final List<Id<CarrierService>> ids=new ArrayList<>();
 private final Map<String,Integer> slots=new HashMap<>();
 final Map<String,Section> sections=new HashMap<>();
 SectionMemberIndex(SectionCatalog catalog){
  for(var section:catalog.sections()){
   int[] members=slots(section.memberIds());var variants=new ArrayList<Variant>();
   for(var v:section.variants())variants.add(new Variant(v.index(),slots(v.orderedJobIds()),v.orderedHaltLinks()));
   if(sections.put(section.id(),new Section(section.id(),section.demand(),members,List.copyOf(variants)))!=null)throw new IllegalArgumentException("Duplicate section " + section.id());
  }
 }
 private int[] slots(List<String> names){return names.stream().mapToInt(name->slots.computeIfAbsent(name,n->{ids.add(Id.create(n,CarrierService.class));return ids.size()-1;})).toArray();}
 CarrierService[] bindings(){return new CarrierService[ids.size()];}
 CarrierService resolve(SectionCatalog catalog,CarrierService[] bindings,int slot){
  var service=bindings[slot];if(service==null){service=catalog.originalCarrier().getServices().get(ids.get(slot));if(service==null)throw new IllegalArgumentException("Missing service " + ids.get(slot));bindings[slot]=service;}return service;
 }
}
