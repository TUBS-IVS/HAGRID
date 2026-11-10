package hagrid.core.simulation;

import com.google.inject.Inject;
import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.*;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.*;
import org.matsim.core.controler.listener.*;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.*;
import java.util.*;

/** Capture each group's completed preceding iteration before the next handler-reset/QSim barrier. */
final class ValidatedCarrierReplanning implements BeforeMobsimListener,IterationEndsListener,ShutdownListener {
    private final Scenario scenario;
    private final Config matsim;
    private final ResolvedHagridConfig config;
    private final Map<String,TravelTime> measured;
    private final ReplanningCoordinator coordinator;
    private final RoutingGraphCapture graphCapture=new RoutingGraphCapture();
    private int runningIteration=-1;
    private int completedIteration=-1;
    private Map<String,TravelTime> completedSources=Map.of();
    @Inject ValidatedCarrierReplanning(Scenario scenario,Config matsim,ResolvedHagridConfig config,Map<String,TravelTime> measured){
        this.scenario=scenario;this.matsim=matsim;this.config=config;this.measured=measured;
        coordinator=new ReplanningCoordinator(config,HAGRIDRouterUtils.resolveSolverSeed(config));
    }
    void startReplanning(int iteration){
        var carriers=CarriersUtils.getCarriers(scenario);
        int start=config.optimization().replanningStartIteration().orElseThrow();
        long jobs=carriers.getCarriers().values().stream().filter(c->!c.getServices().isEmpty()||!c.getShipments().isEmpty()).count();
        long groups=(jobs+config.optimization().carriersPerGroup().orElseThrow()-1)/config.optimization().carriersPerGroup().orElseThrow();
        if(jobs>0 && matsim.controller().getLastIteration()<start+groups-1)
            throw new IllegalArgumentException("Simulation ends before all replanning groups can be validated and driven");
        coordinator.runGroup(iteration,carriers,()->capture(iteration-1,carriers));
    }
    void applyChoices(int iteration,Map<Carrier,NativeCarrierPlanEvolution.Choice> choices){
        var carriers=CarriersUtils.getCarriers(scenario);
        coordinator.evolve(iteration,choices,()->capture(iteration-1,carriers));
    }
    /** EventsHandling resets the measured handlers at this controller boundary. */
    public void notifyBeforeMobsim(BeforeMobsimEvent event){
        completedIteration=-1;completedSources=Map.of();runningIteration=event.getIteration();
    }
    /** Unlike AfterMobsim (also fired in finally on failure), this follows a normally completed iteration. */
    public void notifyIterationEnds(IterationEndsEvent event){
        if(runningIteration!=event.getIteration())
            throw new IllegalStateException("Cannot mark an unstarted measurement iteration completed: "+event.getIteration());
        completedSources=Map.copyOf(measured);completedIteration=event.getIteration();runningIteration=-1;
    }
    private RoutingContext capture(int measurementIteration,Carriers carriers){
        if(completedIteration!=measurementIteration)
            throw new IllegalStateException("Requires completed measurement iteration "+measurementIteration
                    +" before reset; available completed iteration is "+completedIteration);
        var types=new CarrierVehicleTypes();var sources=new TreeMap<String,TravelTime>();var fallback=new TreeMap<String,TravelTime>();
        double horizon=matsim.travelTimeCalculator().getMaxTime();
        for(var carrier:carriers.getCarriers().values()){
            if(carrier.getServices().isEmpty()&&carrier.getShipments().isEmpty())continue;
            String mode=CarrierModeResolver.resolve(carrier).orElseThrow();
            var source=Objects.requireNonNull(completedSources.get(mode),"Missing completed previous-iteration travel times for "+mode);
            if(measured.get(mode)!=source)throw new IllegalStateException("Measured source changed after completed iteration for "+mode);
            sources.put(mode,source);
            fallback.put(mode,(l,t,p,v)->l.getLength()/l.getFreespeed(t));
            for(var v:carrier.getCarrierCapabilities().getCarrierVehicles().values()){
                types.getVehicleTypes().put(v.getType().getId(),v.getType());horizon=Math.max(horizon,v.getLatestEndTime()+1);
            }
        }
        // This is the native measurement raster (900s for Hannover), not the separate 1800s OD routing raster.
        double bin=matsim.travelTimeCalculator().getTraveltimeBinSize();
        if(bin<1||bin!=Math.rint(bin)||bin>Integer.MAX_VALUE)throw new IllegalArgumentException("Snapshot requires an integer travel-time bin width");
        var sourceNetwork=scenario.getNetwork();
        var frozen=graphCapture.capture(sourceNetwork);
        // Native providers keep receiving their canonical live Link objects; profile geometry is frozen.
        var canonicalSources=new TreeMap<String,TravelTime>();
        sources.forEach((mode,provider)->canonicalSources.put(mode,(link,time,person,vehicle)->
                provider.getLinkTravelTime(Objects.requireNonNull(sourceNetwork.getLinks().get(link.getId()),"Measured link disappeared"),time,person,vehicle)));
        var canonicalFallback=new TreeMap<String,TravelTime>();
        fallback.forEach((mode,provider)->canonicalFallback.put(mode,(link,time,person,vehicle)->
                provider.getLinkTravelTime(Objects.requireNonNull(sourceNetwork.getLinks().get(link.getId()),"Fallback link disappeared"),time,person,vehicle)));
        var snapshot=TravelTimeSnapshot.capture(frozen,canonicalSources,canonicalFallback,measurementIteration,horizon,(int)bin);
        graphCapture.requireUnchanged(sourceNetwork,frozen);
        return RoutingContext.create(frozen,types,snapshot,config);
    }
    public void notifyShutdown(ShutdownEvent event){
        try{
            if(!event.isUnexpected()){
                var file=java.nio.file.Path.of(event.getServices().getControlerIO().getOutputFilename("carrier-routing-outcomes.csv"));
                RoutingMetricsCsv.write(coordinator.finalMetrics(),file);
            }
        }catch(java.io.IOException e){
            throw new java.io.UncheckedIOException("Cannot write final carrier routing outcomes",e);
        }finally{
            try{coordinator.close();}finally{graphCapture.close();}
        }
    }
}
