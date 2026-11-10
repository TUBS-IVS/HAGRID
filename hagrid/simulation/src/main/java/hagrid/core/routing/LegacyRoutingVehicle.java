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

import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/** Vehicle adaptation only; registered routing cost rates remain owned by the disutility. */
class LegacyRoutingVehicle implements org.matsim.vehicles.Vehicle {
    private final Id<org.matsim.vehicles.Vehicle> id;
    private final VehicleType type;
    private final Attributes attributes=new AttributesImpl();

    LegacyRoutingVehicle(Vehicle vehicle,String mode) {
        id=Id.createVehicleId(vehicle.getId());
        type=VehicleUtils.createVehicleType(Id.create(vehicle.getType().getTypeId(),VehicleType.class));
        type.setMaximumVelocity(vehicle.getType().getMaxVelocity());
        type.setNetworkMode(mode);
    }
    LegacyRoutingVehicle(CarrierVehicle vehicle){id=vehicle.getId();type=vehicle.getType();}
    public Id<org.matsim.vehicles.Vehicle> getId(){return id;}
    public VehicleType getType(){return type;}
    public Attributes getAttributes(){return attributes;}
}