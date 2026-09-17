package hagrid.utils.routing;


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
 
 import org.matsim.api.core.v01.TransportMode; 

 import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
 
 /**
  * @author steffenaxer & LB
  */
 public interface VRPTransportCostsFactory {
     VRPTransportCosts createVRPTransportCosts();
     Map<String, VRPTransportCosts> createVRPTransportCostsWithModeCongestedTravelTime();    
 }
 
 
 
 