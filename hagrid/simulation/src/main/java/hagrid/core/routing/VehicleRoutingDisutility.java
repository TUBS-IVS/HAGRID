/*******************************************************************************
 * Copyright (c) 2011 Stefan Schroeder.
 * eMail: stefan.schroeder@kit.edu
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     Stefan Schroeder - initial API and implementation
 ******************************************************************************/
package hagrid.core.routing;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import java.util.Map;

/** Immutable vehicle-type rates combined with the selected link travel-time model. */
final class VehicleRoutingDisutility implements TravelDisutility {
    record VariableCosts(double perMeter,double perSecond) {}
    private final Map<String,VariableCosts> rates;
    private final TravelTime travelTime;

    VehicleRoutingDisutility(Map<String,VariableCosts> rates,TravelTime travelTime) {
        this.rates=Map.copyOf(rates);this.travelTime=travelTime;
    }
    public double getLinkTravelDisutility(Link link,double time,Person person,Vehicle vehicle) {
        var costs=rates.get(vehicle.getType().getId().toString());
        if(costs==null)throw new IllegalStateException("type specific costs for "+vehicle.getType().getId()+" are missing.");
        return costs.perMeter()*link.getLength()+costs.perSecond()*travelTime.getLinkTravelTime(link,time,person,vehicle);
    }
    public double getLinkMinimumTravelDisutility(Link link) {
        double minimum=Double.MAX_VALUE,freeTime=link.getLength()/link.getFreespeed();
        for(var costs:rates.values()) {
            double value=costs.perMeter()*link.getLength()+costs.perSecond()*freeTime;
            if(value<minimum)minimum=value;
        }
        return minimum;
    }
}