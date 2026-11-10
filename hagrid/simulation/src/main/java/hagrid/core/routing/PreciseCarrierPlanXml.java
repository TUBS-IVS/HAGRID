package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import javax.xml.stream.*;
import javax.xml.stream.events.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.matsim.freight.carriers.CarrierConstants.*;

/** Preserve doubles in a private result package without changing MATSim's global export time format.
 * Stream the native schema and replace only its rounded time attributes; roads and job metadata remain native.
 */
final class PreciseCarrierPlanXml {
    private PreciseCarrierPlanXml(){}
    static void write(Carrier carrier,Path xml) throws IOException{
        new CarrierPlanWriter(new Carriers(List.of(carrier))).write(xml.toString());
        Path precise=Files.createTempFile(xml.getParent(),".precise-",".xml");
        try{
            rewrite(carrier,xml,precise);Files.move(precise,xml,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(precise);}
    }
    private static void rewrite(Carrier carrier,Path source,Path target) throws IOException{
        var input=XMLInputFactory.newFactory();
        input.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        input.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        var factory=XMLEventFactory.newFactory();
        var tours=carrier.getSelectedPlan().getScheduledTours().iterator();
        ScheduledTour tour=null;Iterator<Tour.Leg> legs=Collections.emptyIterator();
        try(var in=Files.newInputStream(source);var out=Files.newOutputStream(target)){
            var reader=input.createXMLEventReader(in);
            var writer=XMLOutputFactory.newFactory().createXMLEventWriter(out,"UTF-8");
            try{
                while(reader.hasNext()){
                    var event=reader.nextEvent();
                    if(event.isStartElement()){
                        var start=event.asStartElement();var values=new HashMap<String,String>();
                        String tag=start.getName().getLocalPart(),id=attribute(start,ID);
                        switch(tag){
                            case VEHICLE -> {
                                var v=Objects.requireNonNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.createVehicleId(id)));
                                times(values,EARLIEST_START,v.getEarliestStartTime(),LATEST_END,v.getLatestEndTime());
                            }
                            case SERVICE -> {
                                var s=Objects.requireNonNull(carrier.getServices().get(Id.create(id,CarrierService.class)));
                                times(values,EARLIEST_START,s.getServiceStaringTimeWindow().getStart(),LATEST_END,s.getServiceStaringTimeWindow().getEnd());
                                values.put(SERVICE_DURATION,Double.toString(s.getServiceDuration()));
                            }
                            case SHIPMENT -> {
                                var s=Objects.requireNonNull(carrier.getShipments().get(Id.create(id,CarrierShipment.class)));
                                times(values,START_PICKUP,s.getPickupStartingTimeWindow().getStart(),END_PICKUP,s.getPickupStartingTimeWindow().getEnd());
                                times(values,START_DELIVERY,s.getDeliveryStartingTimeWindow().getStart(),END_DELIVERY,s.getDeliveryStartingTimeWindow().getEnd());
                                times(values,PICKUP_SERVICE_TIME,s.getPickupDuration(),DELIVERY_SERVICE_TIME,s.getDeliveryDuration());
                            }
                            case TOUR -> {
                                tour=tours.next();legs=tour.getTour().getTourElements().stream().filter(e->e instanceof Tour.Leg).map(e->(Tour.Leg)e).iterator();
                            }
                            case ACTIVITY -> {
                                if(tour!=null&&START.equals(attribute(start,TYPE)))values.put(END_TIME,Double.toString(tour.getDeparture()));
                            }
                            case LEG -> {
                                var leg=legs.next();times(values,EXPECTED_DEP_TIME,leg.getExpectedDepartureTime(),EXPECTED_TRANSP_TIME,leg.getExpectedTransportTime());
                            }
                            default -> {}
                        }
                        var attributes=new ArrayList<Attribute>();
                        start.getAttributes().forEachRemaining(a->attributes.add(values.containsKey(a.getName().getLocalPart())
                                ?factory.createAttribute(a.getName(),values.get(a.getName().getLocalPart())):a));
                        event=factory.createStartElement(start.getName(),attributes.iterator(),start.getNamespaces());
                    }
                    writer.add(event);
                }
                writer.flush();
            }finally{reader.close();writer.close();}
        }catch(XMLStreamException e){throw new IOException("Cannot preserve precise carrier times",e);}
    }
    private static String attribute(StartElement element,String name){
        var attribute=element.getAttributeByName(new javax.xml.namespace.QName(name));
        return attribute==null?null:attribute.getValue();
    }
    private static void times(Map<String,String> values,String first,double a,String second,double b){
        values.put(first,Double.toString(a));values.put(second,Double.toString(b));
    }
}
