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
import org.matsim.freight.carriers.jsprit.VehicleTypeDependentRoadPricingCalculator;
import org.matsim.vehicles.Vehicle;

/** Adds the configured vehicle-type toll at actual link entry time. */
@SuppressWarnings("deprecation")
final class TollRoutingDisutility implements TravelDisutility {
    private final TravelDisutility base;
    private final VehicleTypeDependentRoadPricingCalculator pricing;
    TollRoutingDisutility(TravelDisutility base,VehicleTypeDependentRoadPricingCalculator pricing) {
        this.base=base;this.pricing=pricing;
    }
    public double getLinkTravelDisutility(Link link,double time,Person person,Vehicle vehicle) {
        return base.getLinkTravelDisutility(link,time,person,vehicle)+pricing.getTollAmount(vehicle.getType().getId(),link,time);
    }
    public double getLinkMinimumTravelDisutility(Link link){return base.getLinkMinimumTravelDisutility(link);}
}