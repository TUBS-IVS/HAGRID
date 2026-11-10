package hagrid.core.routing;


/*
 *   *********************************************************************** *
 *   project: org.matsim.*
 *   *********************************************************************** *
 *                                                                           *
 *   copyright       : (C)  by the members listed in the COPYING,        *
 *                     LICENSE and WARRANTY file.                            *
 *   email           : info at matsim dot org                                *
 *                                                                           *
 *   *********************************************************************** *
 *                                                                           *
 *     This program is free software; you can redistribute it and/or modify  *
 *     it under the terms of the GNU General Public License as published by  *
 *     the Free Software Foundation; either version 2 of the License, or     *
 *     (at your option) any later version.                                   *
 *     See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                           *
 *   ***********************************************************************
 *
 */


import java.util.Map;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

/** Creates one problem adapter over a shared frozen routing context. */
public interface VRPTransportCostsFactory {
    /** Primary HAGRID path: modes, vehicle profiles and preparation mode come from the request. */
    default VRPTransportCosts createVRPTransportCosts(RoutingRequest request) {
        return new ModalTransportCosts(request);
    }

    /** Historical live-provider API; use a request to select the appropriate mode and context. */
    @Deprecated(forRemoval=false)
    VRPTransportCosts createVRPTransportCosts();

    /** Historical modal live-provider registry, retained for the raw legacy simulation constructors. */
    @Deprecated(forRemoval=false)
    Map<String,VRPTransportCosts> createVRPTransportCostsWithModeCongestedTravelTime();
}